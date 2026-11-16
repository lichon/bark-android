package io.github.lichon.abark.cli

import io.github.lichon.abark.apns.IncomingMessage
import io.github.lichon.abark.apns.activation.ActivatedDevice
import io.github.lichon.abark.apns.activation.Albert
import io.github.lichon.abark.apns.activation.AlbertFairPlay
import io.github.lichon.abark.apns.activation.ActivationClient
import io.github.lichon.abark.apns.activation.ActivationOutcome
import io.github.lichon.abark.apns.activation.DeviceKey
import io.github.lichon.abark.apns.activation.DeviceParams
import io.github.lichon.abark.apns.activation.FairPlayCredentials
import io.github.lichon.abark.apns.activation.FairPlayMaterial
import io.github.lichon.abark.apns.identity.DeviceIdentityRecord
import io.github.lichon.abark.apns.identity.FileIdentityStore
import io.github.lichon.abark.apns.identity.IdentityCodec
import io.github.lichon.abark.apns.toHex
import io.github.lichon.abark.apns.transport.CourierConfig
import io.github.lichon.abark.apns.transport.CourierHostProvider
import io.github.lichon.abark.apns.transport.CourierListener
import io.github.lichon.abark.apns.transport.CourierLoop
import io.github.lichon.abark.apns.transport.CourierStatus
import io.github.lichon.abark.apns.transport.TlsCourierChannelFactory
import io.github.lichon.abark.bark.BarkApi
import io.github.lichon.abark.bark.BarkLink
import io.github.lichon.abark.bark.BarkPayload
import io.github.lichon.abark.bark.BarkResult
import io.github.lichon.abark.bark.BarkServer
import io.github.lichon.abark.bark.NotificationSpec
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
abark — CLI harness for the APNs-over-Bark engine (phase 0 gate)

  activate  --store FILE [--fairplay-key FILE --fairplay-chain FILE] [--udid U] [--serial S]
            [--device-class Windows] [--common-name CN] [--endpoint URL]
      Talks to Alberts, writes an activated identity (device key + Apple certificate). Signs with the
      FairPlay material embedded in :core:apns (AlbertFairPlay) unless both files are given.

  show      --store FILE
      Prints what is in the identity file, without any private material.

  run       --store FILE [--server URL --key KEY] [--keepalive SECONDS] [--once] [--register]
      Connects to the APNs courier, registers the scoped token with the bark server, prints pushes.

  push      --server URL --key KEY [--title T] [--body B] [--group G] [--level L]
      Sends a push through the bark server (verifies the whole chain end to end).

  link      LINK
      Parses a bark share link (bark://host/key, https://host/key).

Environment:
  ABARK_FAIRPLAY_KEY, ABARK_FAIRPLAY_CHAIN   alternative to --fairplay-key/--fairplay-chain
                                           (unset: the embedded FairPlay pair is used)
"""

fun main(rawArgs: Array<String>) {
    val command = rawArgs.firstOrNull()
    val rest = rawArgs.drop(1)
    try {
        when (command) {
            "activate" -> activate(Args.parse(rest))
            "show" -> show(Args.parse(rest))
            "run" -> runCourier(Args.parse(rest))
            "push" -> push(Args.parse(rest))
            "link" -> link(rest.firstOrNull() ?: fail("missing LINK"))
            null, "--help", "-h", "help" -> println(USAGE)
            else -> fail("unknown command: $command\n$USAGE")
        }
    } catch (e: UsageException) {
        fail("${e.message}\n$USAGE")
    }
}

private fun activate(args: Args) {
    val storeFile = File(args.required("store"))
    val credentials = loadFairPlayCredentials(args)

    val params = DeviceParams(
        deviceClass = args.string("device-class", DeviceParams.DEFAULT_DEVICE_CLASS),
        uniqueDeviceId = args.string("udid", DeviceParams.random().uniqueDeviceId),
        serialNumber = args.string("serial", "WindowSerial"),
        productType = args.string("product-type", DeviceParams.DEFAULT_PRODUCT_TYPE),
        productVersion = args.string("product-version", DeviceParams.DEFAULT_PRODUCT_VERSION),
        buildVersion = args.string("build-version", DeviceParams.DEFAULT_BUILD_VERSION),
    )

    log("activating as ${params.deviceClass} udid=${params.uniqueDeviceId} serial=${params.serialNumber}")
    val keyPair = DeviceKey.generate()

    val albert = Albert(
        client = ActivationClient(baseUrl = args.string("endpoint", ActivationClient.ALBERT_URL)),
        credentials = credentials,
    )
    val commonName = args.string("common-name", java.util.UUID.randomUUID().toString())
    log("posting activation-info for a ${DeviceKey.modulusBits(keyPair.public)}-bit key (CN $commonName)")

    when (val outcome = albert.activate(params, keyPair, commonName = commonName)) {
        is ActivationOutcome.Activated -> {
            val device: ActivatedDevice = outcome.device
            val record = DeviceIdentityRecord.fromActivatedDevice(
                certificatePem = device.certificatePem,
                params = params,
                keyPair = keyPair,
            )
            FileIdentityStore(storeFile).save(record)
            log("activated; certificate subject=${device.subject}")
            log("certificate valid until ${device.notAfter}")
            log("wrote $storeFile")
            println()
            println(IdentityCodec.encode(record.copy(privateKeyPem = "<redacted>")))
        }

        is ActivationOutcome.Rejected -> fail("activation refused: ${outcome.failure}")
    }
}

private fun show(args: Args) {
    val store = FileIdentityStore(File(args.required("store")))
    val record = store.load() ?: fail("no identity at ${args.required("store")}")
    println("device class : ${record.deviceClass}")
    println("udid         : ${record.uniqueDeviceId}")
    println("serial       : ${record.serialNumber}")
    println("product      : ${record.productType} ${record.productVersion} (${record.buildVersion})")
    println("certificate  : ${runCatching { record.certificate.subjectX500Principal.name }.getOrElse { "unreadable" }}")
    println("valid until  : ${runCatching { record.certificate.notAfter }.getOrElse { null }}")
    println("expired      : ${record.isCertificateExpired()}")
    println("base token   : ${record.baseTokenHex ?: "-"}")
    println("scoped token : ${record.scopedTokenHex ?: "-"} (${record.scopedTopic})")
}

private fun runCourier(args: Args) {
    val storeFile = File(args.required("store"))
    val store = FileIdentityStore(storeFile)
    var record = store.load() ?: fail("no identity at $storeFile — run `activate` first")
    if (record.isCertificateExpired()) log("warning: device certificate has expired; activation will be refused")

    val keepAliveSeconds = args.long("keepalive", 120).toInt()
    val once = args.flag("once")
    val register = args.flag("register") || args.string("server") != null
    val server = resolveServer(args)

    val api = BarkApi()
    var stopRequested = false

    val listener = object : CourierListener {
        override fun onBaseToken(token: ByteArray) {
            log("base token ${token.toHex()}")
            record = record.withTokens(baseToken = token, scopedToken = null)
            store.save(record)
        }

        override fun onScopedToken(token: ByteArray, topicHash: ByteArray) {
            log("scoped token ${token.toHex()} topic=${topicHash.toHex()}")
            record = record.withTokens(baseToken = null, scopedToken = token)
            store.save(record)

            if (server != null && register) {
                when (val result = api.register(server, token.toHex())) {
                    is BarkResult.Ok -> log("registered with ${server.normalizedBaseUrl} (${result.message ?: "ok"})")
                    is BarkResult.Refused -> log("registration refused: ${result.code} ${result.message}")
                    is BarkResult.Unreachable -> log("registration failed: ${result.detail}")
                }
            } else {
                log("not registering: pass --server URL --key KEY to link this device to a bark server")
            }
        }

        override fun onMessage(message: IncomingMessage) {
            val raw = String(message.payload, Charsets.UTF_8)
            println()
            println("─── push ${message.messageId.toHex()} (${raw.length} bytes) ───")
            println(raw)
            val spec = runCatching { NotificationSpec.from(BarkPayload.parse(raw), message.messageId.toHex()) }
                .getOrElse {
                    log("payload is not a bark payload: ${it.message}")
                    null
                }
            spec?.let {
                println(
                    "spec: id=${it.id} urgency=${it.urgency} channel=${it.channelId} " +
                        "group=${it.group} url=${it.url} delete=${it.isDelete} encrypted=${it.encrypted}",
                )
            }
            if (once) {
                stopRequested = true
                log("--once: stopping after the first push")
                loopRef?.stop()
            }
        }

        override fun onUnknownPacket(packet: io.github.lichon.abark.apns.Packet) {
            log("ignored ${packet.type}/${packet.rawType} with ${packet.fields.size} fields")
        }
    }

    val config = CourierConfig(
        topics = listOf(io.github.lichon.abark.apns.Topics.BARK),
        keepAliveIntervalMillis = keepAliveSeconds * 1000,
    )

    val loop = CourierLoop(
        credentials = record.credentials(),
        config = config,
        channelFactory = TlsCourierChannelFactory(record.credentials()) { protocol ->
            log("ALPN negotiated: ${protocol ?: "none"}")
        },
        hostProvider = CourierHostProvider.rotating(),
        listener = listener,
        statusListener = { status ->
            when (status) {
                is CourierStatus.Connecting -> log("connecting to ${status.host} (attempt ${status.attempt})")
                is CourierStatus.Connected -> log("connected to ${status.host}; base token ${status.baseToken.toHex()}")
                is CourierStatus.Reconnecting -> log(
                    "reconnecting to ${status.host ?: "any host"} in ${status.delayMillis}ms after ${status.failure}",
                )
                is CourierStatus.Stopped -> log("stopped: ${status.failure ?: "clean shutdown"}")
                CourierStatus.Idle -> Unit
            }
        },
    )
    loopRef = loop

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log("shutting down")
            loop.stop()
        },
    )

    log("keeping alive every ${keepAliveSeconds}s; Ctrl+C to stop")
    val failure = loop.run()
    if (failure != null && !stopRequested) fail("courier stopped: $failure", 2)
}

/** Set by [runCourier] so the `--once` path can stop the loop from inside the listener. */
private var loopRef: CourierLoop? = null

private fun push(args: Args) {
    val server = resolveServer(args) ?: fail("missing --server and --key")
    val options = buildMap {
        args.string("group")?.let { put("group", it) }
        args.string("level")?.let { put("level", it) }
        args.string("sound")?.let { put("sound", it) }
        args.string("url")?.let { put("url", it) }
    }
    when (val result = BarkApi().push(server, args.string("title", "abark"), args.string("body", "test push"), options)) {
        is BarkResult.Ok -> {
            log("pushed to ${server.normalizedBaseUrl}: ${result.message ?: "ok"}")
            println(result.raw)
        }
        is BarkResult.Refused -> fail("server refused: ${result.code} ${result.message}\n${result.raw}")
        is BarkResult.Unreachable -> fail("server unreachable: ${result.detail}")
    }
}

private fun link(text: String) {
    when (val parsed = BarkLink.parse(text)) {
        is BarkLink.ParseResult.Success -> {
            println("server     : ${parsed.server.normalizedBaseUrl}")
            println("device key : ${parsed.server.deviceKey}")
            println("share link : ${parsed.server.shareLink()}")
        }
        is BarkLink.ParseResult.Failure -> fail(parsed.reason)
    }
}

private fun resolveServer(args: Args): BarkServer? {
    val url = args.string("server") ?: return null
    val key = args.string("key") ?: fail("--server needs --key")
    return BarkServer(url, key)
}

/**
 * Explicit FairPlay material (flags or environment) replaces the embedded pair; a key from one source and a chain from
 * another can never be a valid pair, so the two are all-or-nothing.
 */
private fun loadFairPlayCredentials(args: Args): FairPlayCredentials {
    val keyPath = args.string("fairplay-key") ?: System.getenv("ABARK_FAIRPLAY_KEY")
    val chainPath = args.string("fairplay-chain") ?: System.getenv("ABARK_FAIRPLAY_CHAIN")

    if (keyPath == null && chainPath == null) {
        log("FairPlay: embedded key and certificate chain (AlbertFairPlay)")
        return AlbertFairPlay.credentials
    }
    if (keyPath == null) fail("--fairplay-chain needs --fairplay-key (or ABARK_FAIRPLAY_KEY)")
    if (chainPath == null) fail("--fairplay-key needs --fairplay-chain (or ABARK_FAIRPLAY_CHAIN)")

    log("FairPlay: $keyPath + $chainPath")
    return try {
        FairPlayMaterial.fromPem(readTextFile(keyPath, "fairplay-key"), readTextFile(chainPath, "fairplay-chain"))
    } catch (e: Exception) {
        fail("cannot load FairPlay material: ${e.message}")
    }
}

private fun readTextFile(path: String, what: String): String {
    val file = File(path)
    if (!file.isFile) fail("$what: no such file $path")
    return file.readText()
}

private fun usage() {
    println(USAGE)
    exitProcess(0)
}
