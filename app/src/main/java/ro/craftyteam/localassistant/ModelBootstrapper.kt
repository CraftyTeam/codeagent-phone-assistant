package ro.craftyteam.localassistant

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.ceil

class ModelBootstrapper(private val context: Context) {
    class PreparationException(message: String, val retryable: Boolean) : IOException(message)

    private val modelDir = File(context.filesDir, "models")
    private val modelFile = File(modelDir, BuildConfig.MODEL_FILE_NAME)
    private val partialFile = File(modelDir, "${BuildConfig.MODEL_FILE_NAME}.part")
    private val prefs = context.getSharedPreferences("codeagent_bootstrap", Context.MODE_PRIVATE)

    suspend fun prepare(onProgress: (Long, Long) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            modelDir.mkdirs()
            cleanupOldModels()

            if (isTrustedExistingModel()) {
                onProgress(BuildConfig.MODEL_SIZE_BYTES, BuildConfig.MODEL_SIZE_BYTES)
                return@runCatching modelFile
            }

            if (modelFile.exists()) {
                modelFile.delete()
                clearVerification()
            }

            val remaining = (BuildConfig.MODEL_SIZE_BYTES - partialFile.length()).coerceAtLeast(0L)
            val reserve = 384L * 1024L * 1024L
            val required = remaining + reserve
            if (context.filesDir.usableSpace < required) {
                val requiredMb = ceil(required / 1024.0 / 1024.0).toLong()
                throw PreparationException(
                    "Spațiu insuficient. Eliberează aproximativ $requiredMb MB și încearcă din nou.",
                    false
                )
            }

            download(onProgress)

            if (partialFile.length() != BuildConfig.MODEL_SIZE_BYTES) {
                throw PreparationException("Descărcarea nu este completă. Reiau automat.", true)
            }

            if (!sha256(partialFile).equals(BuildConfig.MODEL_SHA256, ignoreCase = true)) {
                partialFile.delete()
                throw PreparationException("Fișierele descărcate nu au trecut verificarea. Reiau automat.", true)
            }

            if (modelFile.exists()) modelFile.delete()
            if (!partialFile.renameTo(modelFile)) {
                partialFile.copyTo(modelFile, overwrite = true)
                partialFile.delete()
            }

            prefs.edit()
                .putString("verified_sha256", BuildConfig.MODEL_SHA256)
                .putLong("verified_size", modelFile.length())
                .putLong("verified_modified", modelFile.lastModified())
                .apply()

            modelFile
        }
    }

    private fun cleanupOldModels() {
        modelDir.listFiles()?.forEach { file ->
            if (file == modelFile || file == partialFile) return@forEach
            if (file.name.endsWith(".litertlm") || file.name.endsWith(".litertlm.part")) {
                file.delete()
            }
        }
    }

    private fun isTrustedExistingModel(): Boolean {
        if (!modelFile.isFile || modelFile.length() != BuildConfig.MODEL_SIZE_BYTES) return false

        val trusted = prefs.getString("verified_sha256", null)
        val trustedSize = prefs.getLong("verified_size", -1L)
        val trustedModified = prefs.getLong("verified_modified", -1L)

        if (
            trusted.equals(BuildConfig.MODEL_SHA256, ignoreCase = true) &&
            trustedSize == modelFile.length() &&
            trustedModified == modelFile.lastModified()
        ) {
            return true
        }

        val valid = sha256(modelFile).equals(BuildConfig.MODEL_SHA256, ignoreCase = true)
        if (valid) {
            prefs.edit()
                .putString("verified_sha256", BuildConfig.MODEL_SHA256)
                .putLong("verified_size", modelFile.length())
                .putLong("verified_modified", modelFile.lastModified())
                .apply()
        }
        return valid
    }

    private fun clearVerification() {
        prefs.edit().clear().apply()
    }

    private fun download(onProgress: (Long, Long) -> Unit) {
        var existing = partialFile.length().coerceAtMost(BuildConfig.MODEL_SIZE_BYTES)
        if (existing != partialFile.length()) {
            partialFile.delete()
            existing = 0L
        }

        val connection = (URL(BuildConfig.MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "CodeAgent/${BuildConfig.VERSION_NAME} Android")
            setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val response = connection.responseCode
            if (response != HttpURLConnection.HTTP_OK && response != HttpURLConnection.HTTP_PARTIAL) {
                throw PreparationException("Nu mă pot conecta la server. Reiau automat.", true)
            }

            val append = response == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (!append) existing = 0L

            var downloaded = existing
            var lastEmitAt = 0L
            onProgress(downloaded, BuildConfig.MODEL_SIZE_BYTES)

            connection.inputStream.use { input ->
                FileOutputStream(partialFile, append).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmitAt >= 250L || downloaded >= BuildConfig.MODEL_SIZE_BYTES) {
                            lastEmitAt = now
                            onProgress(downloaded, BuildConfig.MODEL_SIZE_BYTES)
                        }
                    }
                    output.fd.sync()
                }
            }
        } catch (error: PreparationException) {
            throw error
        } catch (error: IOException) {
            throw PreparationException("Conexiunea s-a întrerupt. Reiau automat.", true)
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
