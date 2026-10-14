// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

/** Source kinds persisted as [SourceEntity.kind] strings. */
enum class SourceKind(val raw: String) {
    LOCAL("local"),
    SMB("smb"),
    NFS("nfs"),
    WEBDAV("webdav"),
    ASMRONE("asmrone"),
    DLSITE("dlsite");

    companion object {
        fun of(raw: String): SourceKind? = entries.firstOrNull { it.raw == raw }
    }
}

val SourceEntity.kindType: SourceKind?
    get() = SourceKind.of(kind)
