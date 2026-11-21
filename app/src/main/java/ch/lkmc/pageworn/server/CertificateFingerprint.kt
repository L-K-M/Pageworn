package ch.lkmc.pageworn.server

import java.security.MessageDigest
import java.security.cert.X509Certificate

fun sha256Fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString(":") { "%02X".format(it.toInt() and 0xff) }
