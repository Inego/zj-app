package inego.zungjunggame.lwjgl3;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/** advapi32 credential APIs. jna-platform 5.18 does not ship {@code WinCred}. */
interface Advapi32Creds extends StdCallLibrary {
    Advapi32Creds INSTANCE = Native.load("advapi32", Advapi32Creds.class, W32APIOptions.UNICODE_OPTIONS);

    int CRED_TYPE_GENERIC = 1;
    int CRED_PERSIST_LOCAL_MACHINE = 2;
    int ERROR_NOT_FOUND = 1168;

    boolean CredWrite(CREDENTIAL credential, int flags);

    boolean CredRead(String targetName, int type, int flags, PointerByReference pcred);

    boolean CredDelete(String targetName, int type, int flags);

    void CredFree(Pointer cred);

    @Structure.FieldOrder({
        "Flags", "Type", "TargetName", "Comment", "LastWrittenLow", "LastWrittenHigh",
        "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount",
        "Attributes", "TargetAlias", "UserName"
    })
    class CREDENTIAL extends Structure {
        public int Flags;
        public int Type;
        public String TargetName;
        public String Comment;
        public int LastWrittenLow;
        public int LastWrittenHigh;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public String TargetAlias;
        public String UserName;

        public CREDENTIAL() {
        }

        public CREDENTIAL(Pointer p) {
            super(p);
            read();
        }
    }
}
