# Changelog

## 1.0.2
- **Security Patches**:
  - Fixed unauthenticated path traversal vulnerability in `Ps4HttpServer` (CWE-22) to prevent unauthorized file access outside the served root.
  - Fixed zip-slip vulnerability in toolkit extraction (`extractZip`).
- **Privacy & Permissions**:
  - Migrated custom folder selection to Storage Access Framework (SAF) folder picker (`ACTION_OPEN_DOCUMENT_TREE`), completely removing all dangerous storage permissions (`MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`).
- **Toolkit Updates**:
  - Updated supported official toolkit ZIPs with SHA-256 verification:
    - `ows4.rawgame4.v1.zip` (`3024070420f67d102d886cc9e785e36b70144693e50ca9ddee35c8a6dfdc1185`)
    - `ows4.rawgame4.v2.zip` (`5ad8785f53a6f565fd70c14a0791cc34fdf7798e1633ae7b2a8b520740701c15`)
    - `ows4.raw13g.v1.zip` (`7b000d912db10ed2cd638c30a9ac794a2d0e87394090396a1305c67e1c8fc16b`)
- Rebranded project from PS4 OWS to OWS4.

## 1.0.1
- Added **Select Method** feature allowing users to choose between **Default Toolkit** and **Custom** hosting methods.

## 1.0.0-beta
- Initial release of OWS4.
- Specialized HTTP server for offline hosting.
- Integrated SHA-256 file integrity verification.
