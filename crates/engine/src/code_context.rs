//! Local inspection CLI. Reuses the same bounded APIs as the IDE, without a
//! second daemon or edits to users' global agent configuration.
use history::HistoryStore;
use protocol::{ContextQuery, LearningWrite};
use std::{ffi::OsString, io::Read, path::PathBuf};
use tokio_util::sync::CancellationToken;

pub fn run(args: Vec<OsString>) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut args = args.into_iter();
    let action = args
        .next()
        .and_then(|v| v.into_string().ok())
        .ok_or("missing context action")?;
    let mut project = None;
    let mut data = None;
    let mut params = serde_json::json!({});
    while let Some(arg) = args.next() {
        match arg.to_str() {
            Some("--project-root")=>project=Some(PathBuf::from(args.next().ok_or("missing project root")?)),
            Some("--data-root")=>data=Some(PathBuf::from(args.next().ok_or("missing data root")?)),
            Some("--params")=> {
                let input=args.next().ok_or("missing context parameters")?.into_string().map_err(|_|"invalid context parameters")?;
                if input.len()>16384 {return Err("context parameters exceed 16 KiB".into());}
                params=serde_json::from_str(&input).map_err(|_|"invalid context JSON")?;
            }
            Some("--params-file")=> {
                let path=PathBuf::from(args.next().ok_or("missing context parameters file")?);
                let mut input=String::new();
                std::fs::File::open(path)?.take(16385).read_to_string(&mut input)?;
                if input.len()>16384 {return Err("context parameters exceed 16 KiB".into());}
                params=serde_json::from_str(&input).map_err(|_|"invalid context JSON")?;
            }
            _=>return Err("expected --project-root PATH [--data-root PATH] [--params JSON | --params-file PATH]".into()),
        }
    }
    let method = match action.as_str() {
        "init" => "initialize",
        "find" => "find_code",
        "grep" => "find_all",
        "file" => "get_file",
        "api" => "file_api",
        "trace" => "trace_calls",
        "map" => "repo_map",
        "graph" => "get_graph",
        "check" => "check_index",
        "learnings" => "list_learnings",
        "review" => "review_learnings",
        "branches" => "branch_overlap",
        "note" => "save_learning",
        _ => return Err("unknown context action".into()),
    };
    let project = project.ok_or("--project-root is required")?;
    let store = match data {
        Some(data) => HistoryStore::open(&project, &data)?,
        None => HistoryStore::open_default(&project)?,
    };
    let cancel = CancellationToken::new();
    let result = match method {
        "initialize" => runtime::context_bridge::initialize(&store, &project, &cancel)?,
        "save_learning" => {
            let request: LearningWrite =
                serde_json::from_value(params).map_err(|_| "invalid learning parameters")?;
            runtime::context_bridge::save_learning(&store, &request, &cancel)?
        }
        _ => {
            let request: ContextQuery =
                serde_json::from_value(params).map_err(|_| "invalid context parameters")?;
            runtime::context_bridge::project_query(&store, method, &request, &cancel)?
        }
    };
    println!("{}", serde_json::to_string_pretty(&result)?);
    Ok(())
}
