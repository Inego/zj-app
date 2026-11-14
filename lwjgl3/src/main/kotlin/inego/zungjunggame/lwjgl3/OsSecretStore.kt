package inego.zungjunggame.lwjgl3

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Tiny string secret in the OS credential store. Holds the AES wrapping key
 * for [DesktopOsBox], never the refresh token itself.
 */
internal interface OsSecretStore {
    fun write(value: String): Boolean
    fun read(): String?
    fun delete(): Boolean

    companion object {
        const val SERVICE = "inego.zungjunggame.auth"
        const val ACCOUNT = "refresh-key"
        const val WINDOWS_TARGET = "inego.zungjunggame/auth.refresh-key"

        fun open(): OsSecretStore? = try {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            when {
                os.contains("win") -> WindowsCredentialStore()
                os.contains("mac") -> MacKeychainStore()
                else -> LinuxSecretServiceStore.open()
            }
        } catch (t: Throwable) {
            System.err.println("ZjAuthStore: OS secret store unavailable (${t.javaClass.simpleName})")
            null
        }
    }
}

/** Windows Credential Manager generic credential (`CredRead` / `CredWrite`). */
internal class WindowsCredentialStore(
    private val target: String = OsSecretStore.WINDOWS_TARGET,
) : OsSecretStore {
    override fun write(value: String): Boolean = try {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val mem = Memory(bytes.size.toLong())
        mem.write(0, bytes, 0, bytes.size)
        val cred = Advapi32Creds.CREDENTIAL()
        cred.Type = Advapi32Creds.CRED_TYPE_GENERIC
        cred.TargetName = target
        cred.UserName = "zj-game"
        cred.CredentialBlobSize = bytes.size
        cred.CredentialBlob = mem
        cred.Persist = Advapi32Creds.CRED_PERSIST_LOCAL_MACHINE
        Advapi32Creds.INSTANCE.CredWrite(cred, 0)
    } catch (t: Throwable) {
        System.err.println("ZjAuthStore: CredWrite: ${t.javaClass.simpleName}")
        false
    }

    override fun read(): String? = try {
        val pref = PointerByReference()
        if (!Advapi32Creds.INSTANCE.CredRead(target, Advapi32Creds.CRED_TYPE_GENERIC, 0, pref)) {
            val err = Native.getLastError()
            if (err != Advapi32Creds.ERROR_NOT_FOUND) {
                System.err.println("ZjAuthStore: CredRead: $err")
            }
            return null
        }
        try {
            val cred = Advapi32Creds.CREDENTIAL(pref.value)
            val size = cred.CredentialBlobSize
            val blob = cred.CredentialBlob
            if (size <= 0 || blob == null) "" else String(blob.getByteArray(0, size), StandardCharsets.UTF_8)
        } finally {
            Advapi32Creds.INSTANCE.CredFree(pref.value)
        }
    } catch (t: Throwable) {
        System.err.println("ZjAuthStore: CredRead: ${t.javaClass.simpleName}")
        null
    }

    override fun delete(): Boolean = try {
        Advapi32Creds.INSTANCE.CredDelete(target, Advapi32Creds.CRED_TYPE_GENERIC, 0) ||
            Native.getLastError() == Advapi32Creds.ERROR_NOT_FOUND
    } catch (t: Throwable) {
        System.err.println("ZjAuthStore: CredDelete: ${t.javaClass.simpleName}")
        false
    }
}

/** macOS Keychain generic password (`SecKeychain*` via JNA). */
internal class MacKeychainStore(
    private val service: String = OsSecretStore.SERVICE,
    private val account: String = OsSecretStore.ACCOUNT,
) : OsSecretStore {
    private val security: SecurityLib by lazy { Native.load("Security", SecurityLib::class.java) }
    private val coreFoundation: CoreFoundationLib by lazy {
        Native.load("CoreFoundation", CoreFoundationLib::class.java)
    }

    override fun write(value: String): Boolean = try {
        val serviceBytes = service.toByteArray(StandardCharsets.UTF_8)
        val accountBytes = account.toByteArray(StandardCharsets.UTF_8)
        val data = value.toByteArray(StandardCharsets.UTF_8)
        val itemRef = PointerByReference()
        val length = IntByReference()
        val content = PointerByReference()
        val found = security.SecKeychainFindGenericPassword(
            null,
            serviceBytes.size, serviceBytes,
            accountBytes.size, accountBytes,
            length, content, itemRef,
        )
        when (found) {
            ERR_SEC_SUCCESS -> {
                if (content.value != null) {
                    security.SecKeychainItemFreeContent(null, content.value)
                }
                val mod = security.SecKeychainItemModifyAttributesAndData(
                    itemRef.value, null, data.size, data,
                )
                if (itemRef.value != null) coreFoundation.CFRelease(itemRef.value)
                mod == ERR_SEC_SUCCESS
            }
            ERR_SEC_ITEM_NOT_FOUND -> {
                security.SecKeychainAddGenericPassword(
                    null,
                    serviceBytes.size, serviceBytes,
                    accountBytes.size, accountBytes,
                    data.size, data,
                    null,
                ) == ERR_SEC_SUCCESS
            }
            else -> {
                System.err.println("ZjAuthStore: SecKeychainFind: $found")
                false
            }
        }
    } catch (t: Throwable) {
        System.err.println("ZjAuthStore: Keychain write: ${t.javaClass.simpleName}")
        false
    }

    override fun read(): String? = try {
        val serviceBytes = service.toByteArray(StandardCharsets.UTF_8)
        val accountBytes = account.toByteArray(StandardCharsets.UTF_8)
        val length = IntByReference()
        val content = PointerByReference()
        val found = security.SecKeychainFindGenericPassword(
            null,
            serviceBytes.size, serviceBytes,
            accountBytes.size, accountBytes,
            length, content, null,
        )
        when (found) {
            ERR_SEC_SUCCESS -> {
                try {
                    val ptr = content.value
                    val size = length.value
                    if (ptr == null || size <= 0) "" else String(ptr.getByteArray(0, size), StandardCharsets.UTF_8)
                } finally {
                    if (content.value != null) {
                        security.SecKeychainItemFreeContent(null, content.value)
                    }
                }
            }
            ERR_SEC_ITEM_NOT_FOUND -> null
            else -> {
                System.err.println("ZjAuthStore: SecKeychainFind: $found")
                null
            }
        }
    } catch (t: Throwable) {
        System.err.println("ZjAuthStore: Keychain read: ${t.javaClass.simpleName}")
        null
    }

    override fun delete(): Boolean = try {
        val serviceBytes = service.toByteArray(StandardCharsets.UTF_8)
        val accountBytes = account.toByteArray(StandardCharsets.UTF_8)
        val itemRef = PointerByReference()
        val length = IntByReference()
        val content = PointerByReference()
        val found = security.SecKeychainFindGenericPassword(
            null,
            serviceBytes.size, serviceBytes,
            accountBytes.size, accountBytes,
            length, content, itemRef,
        )
        if (found == ERR_SEC_ITEM_NOT_FOUND) return true
        if (found != ERR_SEC_SUCCESS) return false
        if (content.value != null) {
            security.SecKeychainItemFreeContent(null, content.value)
        }
        val deleted = security.SecKeychainItemDelete(itemRef.value)
        if (itemRef.value != null) coreFoundation.CFRelease(itemRef.value)
        deleted == ERR_SEC_SUCCESS
    } catch (t: Throwable) {
        System.err.println("ZjAuthStore: Keychain delete: ${t.javaClass.simpleName}")
        false
    }

    private companion object {
        const val ERR_SEC_SUCCESS = 0
        const val ERR_SEC_ITEM_NOT_FOUND = -25300
    }
}

private interface SecurityLib : Library {
    fun SecKeychainAddGenericPassword(
        keychain: Pointer?,
        serviceNameLength: Int,
        serviceName: ByteArray,
        accountNameLength: Int,
        accountName: ByteArray,
        passwordLength: Int,
        passwordData: ByteArray,
        itemRef: PointerByReference?,
    ): Int

    fun SecKeychainFindGenericPassword(
        keychainOrArray: Pointer?,
        serviceNameLength: Int,
        serviceName: ByteArray,
        accountNameLength: Int,
        accountName: ByteArray,
        passwordLength: IntByReference,
        passwordData: PointerByReference,
        itemRef: PointerByReference?,
    ): Int

    fun SecKeychainItemModifyAttributesAndData(
        itemRef: Pointer,
        attrList: Pointer?,
        length: Int,
        data: ByteArray,
    ): Int

    fun SecKeychainItemFreeContent(attrList: Pointer?, data: Pointer): Int

    fun SecKeychainItemDelete(itemRef: Pointer): Int
}

private interface CoreFoundationLib : Library {
    fun CFRelease(ref: Pointer)
}

/**
 * Linux Secret Service via `secret-tool` (libsecret). Missing binary or a
 * locked/absent session bus is treated as unavailable.
 */
internal class LinuxSecretServiceStore(
    private val service: String = OsSecretStore.SERVICE,
    private val account: String = OsSecretStore.ACCOUNT,
) : OsSecretStore {
    override fun write(value: String): Boolean {
        val process = start(
            "secret-tool", "store", "--label=zj-game auth",
            "service", service, "account", account,
        ) ?: return false
        return try {
            process.outputStream.use { out ->
                out.write(value.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            finish(process, ignoreStdout = true) != null
        } catch (t: Throwable) {
            process.destroyForcibly()
            System.err.println("ZjAuthStore: secret-tool store: ${t.javaClass.simpleName}")
            false
        }
    }

    override fun read(): String? {
        val process = start(
            "secret-tool", "lookup",
            "service", service, "account", account,
        ) ?: return null
        return try {
            finish(process, ignoreStdout = false)
        } catch (t: Throwable) {
            process.destroyForcibly()
            System.err.println("ZjAuthStore: secret-tool lookup: ${t.javaClass.simpleName}")
            null
        }
    }

    override fun delete(): Boolean {
        val process = start(
            "secret-tool", "clear",
            "service", service, "account", account,
        ) ?: return false
        return try {
            finish(process, ignoreStdout = true) != null
        } catch (t: Throwable) {
            process.destroyForcibly()
            System.err.println("ZjAuthStore: secret-tool clear: ${t.javaClass.simpleName}")
            false
        }
    }

    companion object {
        private const val TIMEOUT_SEC = 15L

        fun open(): LinuxSecretServiceStore? =
            if (commandOnPath("secret-tool")) LinuxSecretServiceStore() else null

        private fun commandOnPath(name: String): Boolean {
            val path = System.getenv("PATH") ?: return false
            return path.split(File.pathSeparatorChar).any { dir ->
                File(dir, name).canExecute()
            }
        }

        private fun start(vararg command: String): Process? = try {
            ProcessBuilder(*command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (t: Throwable) {
            System.err.println("ZjAuthStore: secret-tool: ${t.javaClass.simpleName}")
            null
        }

        private fun finish(process: Process, ignoreStdout: Boolean): String? {
            if (!process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            val stdout = process.inputStream.readBytes()
            if (process.exitValue() != 0) return null
            if (ignoreStdout) return ""
            return String(stdout, StandardCharsets.UTF_8)
        }
    }
}
