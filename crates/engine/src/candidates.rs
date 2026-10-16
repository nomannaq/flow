use history::{ContentId, HistoryStore};
use protocol::{CandidateQuery, Method};
use serde_json::{Value, json};

pub fn request(store: HistoryStore, method: Method, params: Value) -> history::Result<Value> {
    let invalid = || {
        history::Error::Invalid(
            "candidate request rejected; close writers, refresh and review again",
        )
    };
    match method {
        Method::CandidatePrepare => {
            return serde_json::to_value(
                runtime::review::prepare(
                    store,
                    serde_json::from_value(params).map_err(|_| invalid())?,
                )
                .map_err(|_| invalid())?,
            )
            .map_err(|_| invalid());
        }
        Method::CandidateDecide => {
            return serde_json::to_value(
                runtime::review::decide(
                    store,
                    serde_json::from_value(params).map_err(|_| invalid())?,
                )
                .map_err(|_| invalid())?,
            )
            .map_err(|_| invalid());
        }
        Method::CandidateCheck => {
            return runtime::review::check(
                store,
                serde_json::from_value(params).map_err(|_| invalid())?,
            )
            .map_err(|_| invalid());
        }
        _ => {}
    }
    let query: CandidateQuery = serde_json::from_value(params).map_err(|_| invalid())?;
    let id = ContentId::try_from(query.candidate_id).map_err(|_| invalid())?;
    let review = store.candidate_review(id.clone())?;
    if method == Method::CandidateGet {
        let source = store.candidate_source(review.candidate.session_id.clone());
        let fresh = source.is_ok_and(|s| {
            s.source == review.candidate.source
                && s.baseline == review.candidate.baseline
                && s.generation == review.candidate.generation
        });
        let session = store.get_session(review.candidate.session_id.clone())?;
        let workspace = store.get_workspace(session.workspace_id)?;
        let mut value = serde_json::to_value(review).map_err(|_| invalid())?;
        value["baseCommit"] = json!(workspace.base_commit);
        value["sourceGenerationCurrent"] = json!(fresh);
        return Ok(value);
    }
    let changes = store.candidate_changes(id.clone())?;
    if method == Method::CandidateFiles {
        let start = query
            .after
            .as_deref()
            .unwrap_or("0")
            .parse::<usize>()
            .map_err(|_| invalid())?;
        if start > changes.len() {
            return Err(invalid());
        }
        let end = start.saturating_add(20).min(changes.len());
        let items=changes[start..end].iter().enumerate().map(|(i,c)|json!({"index":start+i,"oldPath":c.old_path,"newPath":c.new_path,"beforeBytes":c.before.as_ref().map(|f|f.size),"afterBytes":c.after.as_ref().map(|f|f.size),"beforeMode":c.before.as_ref().map(|f|f.mode),"afterMode":c.after.as_ref().map(|f|f.mode),"contentIds":{"before":c.before.as_ref().map(|f|&f.blob),"after":c.after.as_ref().map(|f|&f.blob)}})).collect::<Vec<_>>();
        return Ok(
            json!({"candidateId":id,"items":items,"total":changes.len(),"next":(end<changes.len()).then(||end.to_string())}),
        );
    }
    let change = changes.get(query.file_index).ok_or_else(invalid)?;
    let (before, before_note) = crate::session_history::text(&store, change.before.as_ref())?;
    let (after, after_note) = crate::session_history::text(&store, change.after.as_ref())?;
    Ok(
        json!({"candidateId":id,"fileIndex":query.file_index,"oldPath":change.old_path,"newPath":change.new_path,"before":before,"after":after,"beforeNote":before_note,"afterNote":after_note,"beforeMode":change.before.as_ref().map(|f|f.mode),"afterMode":change.after.as_ref().map(|f|f.mode),"contentIds":{"before":change.before.as_ref().map(|f|&f.blob),"after":change.after.as_ref().map(|f|&f.blob)}}),
    )
}
