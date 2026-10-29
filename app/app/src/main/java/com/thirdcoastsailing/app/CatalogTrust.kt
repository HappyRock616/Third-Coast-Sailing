package com.thirdcoastsailing.app

import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/*
 * Trust checks for everything the app takes in from outside: the chart catalog, chart files and downloads.
 *
 *  - The catalog (catalog.json on the "charts" GitHub release) must carry a valid signature (catalog.json.sig) made
 *    with the project's private key, which is kept off GitHub. The public half is below. So even someone who could
 *    change the release files can't make the app install altered charts: the checksums in the catalog are only
 *    trusted because the catalog itself is signed.
 *  - Names, ids and links from the catalog are checked against strict patterns before they are used, so a file name
 *    can never point outside the app's chart folders and downloads only come from this project's release.
 *  - Downloads are read with a size limit, so a misbehaving server can't fill the phone's memory or storage.
 */
object CatalogTrust {

    /** ECDSA P-256 public key (X.509 / SubjectPublicKeyInfo, base64) that the chart catalog must be signed with. */
    private const val CATALOG_PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEr5tDN0yBqGhCzBoxapLfOaIXb0sBvprfMYY/ssGyUnbS25mZYsaNYJ89muYD1suFFcPW1QucHZWfjgQksuYYEg=="

    /** Chart files may only be downloaded from this project's "charts" release. */
    const val RELEASE_PREFIX = "https://github.com/HappyRock616/Third-Coast-Sailing/releases/download/charts/"

    /** Largest catalog and signature accepted (the real ones are a few KB and about 100 bytes). */
    const val MAX_CATALOG_BYTES = 1_000_000
    const val MAX_SIG_BYTES = 4_096

    /** Largest single chart file accepted, packed or unpacked (the biggest today is under 70 MB). */
    const val MAX_CHART_FILE_BYTES = 1_500_000_000L

    /** Lake ids: lowercase letters, digits and "_" ("michigan"). */
    private val ID = Regex("^[a-z0-9_]{1,32}$")
    /** File names: lowercase letters, digits, "_" and "-", with dotted extensions ("michigan_20261022_chart.pmtiles"). */
    private val NAME = Regex("^[a-z0-9_-]{1,80}(\\.[a-z0-9]{1,10}){1,3}$")
    /** NOAA buoy station ids ("45007", "SGNW3"). */
    private val STATION = Regex("^[A-Z0-9]{3,8}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** True for a plain lake id ("michigan"). */
    fun validId(s: String) = ID.matches(s)
    /** True for a plain file name that can't leave its folder ("michigan_20261022_chart.pmtiles"). */
    fun validName(s: String) = NAME.matches(s)
    /** True for a plain NOAA station id ("45007"). */
    fun validStation(s: String) = STATION.matches(s)
    /** True for a 64-character lowercase hex SHA-256. */
    fun validSha(s: String) = HEX64.matches(s)
    /** True for a download link on this project's charts release with a plain file name. */
    fun validUrl(s: String) = s.startsWith(RELEASE_PREFIX) && validName(s.removePrefix(RELEASE_PREFIX))

    /** True when `sigBase64` is a valid signature of exactly `data` by the catalog key. */
    fun verifyCatalog(data: ByteArray, sigBase64: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(CATALOG_PUBLIC_KEY, Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(Base64.decode(sigBase64.trim(), Base64.DEFAULT))
        }
    }.getOrDefault(false)

    /** Read a whole stream, failing when it is longer than `max` bytes. */
    fun readCapped(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 14)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) throw IOException("Download too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Copy a stream, failing when more than `max` bytes come through. Returns the number of bytes copied. */
    fun copyCapped(input: InputStream, output: OutputStream, max: Long): Long {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("File too large")
            output.write(buf, 0, n)
        }
        return total
    }

    /** SHA-256 of a file, as lowercase hex. */
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 16).use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
