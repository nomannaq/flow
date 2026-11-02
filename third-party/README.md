# Supplemental dependency licenses

The published `rmcp` and `rmcp-macros` 3.5.1 crate archives declare Apache-2.0 but
omit the license file. The copies here come from the exact commit recorded in
those archives' `.cargo_vcs_info.json`:

https://github.com/modelcontextprotocol/rust-sdk/blob/79437f291b2c44053d00dcd5db969fd0cca7c887/LICENSE

`package_native_engine.py` includes version-specific supplemental notices in the
existing dependency inventory and plugin ZIP. Nothing is fetched during packaging.
