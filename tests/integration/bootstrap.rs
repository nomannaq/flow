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

#[cfg(target_os = "linux")]
#[test]
fn first_start_creates_external_xdg_data_but_refuses_a_location_inside_the_project() {
    let scratch = tempfile::tempdir().unwrap();
    let project = scratch.path().join("project with spaces");
    std::fs::create_dir(&project).unwrap();
    for (base, accepted) in [
        (scratch.path().join("new native data/share"), true),
        (project.join("forbidden/data"), false),
    ] {
        let output = Command::new(env!("CARGO_BIN_EXE_flow-engine"))
            .args(["--project-root", project.to_str().unwrap()])
            .env("XDG_DATA_HOME", &base)
            .stdin(Stdio::null())
            .output()
            .unwrap();
        assert_eq!(output.status.success(), accepted, "{output:?}");
        assert_eq!(base.join("Flow").is_dir(), accepted);
        assert_eq!(project.read_dir().unwrap().count(), 0);
    }
}
