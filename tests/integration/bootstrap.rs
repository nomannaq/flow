use std::process::{Command, Stdio};

#[test]
fn bootstrap_keeps_stdout_and_workspace_clean() {
    let workspace = tempfile::tempdir().expect("temporary workspace");
    let output = Command::new(env!("CARGO_BIN_EXE_flow-engine"))
        .current_dir(workspace.path())
        .env("RUST_LOG", "info")
        .stdin(Stdio::null())
        .output()
        .expect("launch Flow engine");

    assert!(output.status.success(), "{output:?}");
    assert!(output.stdout.is_empty(), "stdout is reserved for IPC");
    assert!(
        String::from_utf8_lossy(&output.stderr).contains("Flow engine bootstrap ready"),
        "bootstrap diagnostic must reach stderr: {output:?}"
    );
    assert_eq!(workspace.path().read_dir().unwrap().count(), 0);
}
