package io.github.lichon.abark.apns.activation

import java.math.BigInteger
import java.security.KeyPair
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** Self-signed certificate for tests: stands in for the one Alberts issues over a device key. */
internal object TestCertificates {

    fun selfSignedPem(
        keyPair: KeyPair,
        subject: String = "CN=Test Device,OU=iPhone,O=Apple Inc.,L=Cupertino,ST=CA,C=US",
        days: Long = 30,
    ): String {
        val now = System.currentTimeMillis()
        val name = X500Name(subject)
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            Date(now - 3_600_000),
            Date(now + days * 86_400_000),
            name,
            keyPair.public,
        )
        val holder = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        return Pem.encode("CERTIFICATE", holder.encoded)
    }
}
