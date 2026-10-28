use history::{ContentId, HistoryStore};
use protocol::{ContextQuery, LearningWrite, Method, ProtocolStore, UtilityRequest};
use serde_json::{Value, json};
use tokio_util::sync::CancellationToken;

pub async fn request(
    store: HistoryStore,
    method: Method,
    params: Value,
    cancel: CancellationToken,
) -> Value {
    if method == Method::UtilityGenerate {
        let Ok(request) = serde_json::from_value::<UtilityRequest>(params) else {
            return json!({"error":"Invalid utility request"});
        };
        return runtime::utilities::generate(request, cancel).await;
    }
    tokio::task::spawn_blocking(move || -> history::Result<Value> {
        if method == Method::UtilityPrepare {
            let text = params["input"]
                .as_str()
                .filter(|s| !s.is_empty() && s.len() <= 65536)
                .ok_or(history::Error::Invalid("Input must be 1–65536 bytes"))?;
            return Ok(json!({"inputHash":ContentId::hash(text.as_bytes()),"bytes":text.len()}));
        }
        if method == Method::ContextSaveLearning {
            let request: LearningWrite = serde_json::from_value(params)
                .map_err(|_| history::Error::Invalid("Invalid learning request"))?;
            return runtime::context_bridge::save_learning(&store, &request, &cancel);
        }
        if matches!(
            method,
            Method::ContextPreview
                | Method::ContextOverview
                | Method::ContextSearch
                | Method::ContextSymbol
                | Method::ContextFreshness
                | Method::ContextFind
                | Method::ContextFindAll
                | Method::ContextFile
                | Method::ContextFileApi
                | Method::ContextTrace
                | Method::ContextMap
                | Method::ContextGraph
                | Method::ContextCheck
                | Method::ContextLearnings
                | Method::ContextReview
                | Method::ContextBranches
        ) {
            let query: ContextQuery = serde_json::from_value(params)
                .map_err(|_| history::Error::Invalid("Invalid context request"))?;
            let name = match method {
                Method::ContextPreview => "prompt_context",
                Method::ContextFind => "find_code",
                Method::ContextFindAll => "find_all",
                Method::ContextFile => "get_file",
                Method::ContextFileApi => "file_api",
                Method::ContextTrace => "trace_calls",
                Method::ContextMap => "repo_map",
                Method::ContextGraph => "get_graph",
                Method::ContextCheck => "check_index",
                Method::ContextLearnings => "list_learnings",
                Method::ContextReview => "review_learnings",
                Method::ContextBranches => "branch_overlap",

                Method::ContextSearch => "search_symbols",
                Method::ContextSymbol => "get_symbol_context",
                Method::ContextFreshness => "check_context_freshness",
                _ => "repo_overview",
            };
            return runtime::context_bridge::project_query(&store, name, &query, &cancel);
        }
        let snapshot = store.snapshot()?;
        let root = std::path::Path::new(
            snapshot.state["projectRoot"]
                .as_str()
                .ok_or(history::Error::NotFound)?,
        );
        let result = match method {
            Method::ContextInitialize => {
                return runtime::context_bridge::initialize(&store, root, &cancel);
            }
            Method::OverlayPreview => runtime::local_changes::preview(root),
            Method::CommitSelection => {
                runtime::local_changes::staged(root, params["mode"].as_str().unwrap_or(""))
            }
            _ => return Err(history::Error::Invalid("Unsupported productivity request")),
        };
        // Workspace errors have static, sanitized Display implementations.
        Ok(result.unwrap_or_else(|e| json!({"error":e.to_string()})))
    })
    .await
    .unwrap_or(Err(history::Error::WorkerStopped))
    .unwrap_or_else(|e| json!({"error":e.to_string()}))
}
