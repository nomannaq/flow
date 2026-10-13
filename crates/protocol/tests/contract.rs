use protocol::*;
use serde::{Serialize, de::DeserializeOwned};
use serde_json::{Value, json};

fn round_trip<T: DeserializeOwned + Serialize>(value: &Value) {
    let decoded: T = serde_json::from_value(value.clone()).unwrap();
    assert_eq!(&serde_json::to_value(decoded).unwrap(), value);
}

#[test]
fn shared_golden_contract_and_schema_do_not_drift() {
    let f: Value =
        serde_json::from_str(include_str!("../../../tests/fixtures/protocol/golden.json")).unwrap();
    for key in ["initializeRequest", "healthRequest"] {
        round_trip::<Request>(&f[key]);
    }
    for key in [
        "initializeResponse",
        "healthResponse",
        "errorResponse",
        "shutdownResponse",
        "parseErrorResponse",
    ] {
        round_trip::<Response>(&f[key]);
    }
    round_trip::<InitializeParams>(&f["initializeRequest"]["params"]);
    round_trip::<InitializeResult>(&f["initializeResponse"]["result"]);
    round_trip::<HealthParams>(&f["healthRequest"]["params"]);
    round_trip::<HealthResult>(&f["healthResponse"]["result"]);
    round_trip::<Notification>(&f["cancelNotification"]);
    round_trip::<CancelParams>(&f["cancelNotification"]["params"]);
    round_trip::<DurableEvent>(&f["durableEvent"]);
    round_trip::<MutationReceipt>(&f["mutationReceipt"]);
    round_trip::<StateSnapshot>(&f["stateSnapshot"]);
    round_trip::<ReplayParams>(&f["replayParams"]);
    round_trip::<ReplayPage>(&f["replayPage"]);
    let checked_in: Value =
        serde_json::from_str(include_str!("../../../tests/fixtures/protocol/schema.json")).unwrap();
    assert_eq!(
        schema(),
        checked_in,
        "regenerate and review the shared schema"
    );
}

#[test]
fn additive_fields_and_unknown_values_are_safe() {
    let params: InitializeParams = serde_json::from_value(json!({
        "protocolVersion":1,"clientName":"未来","clientVersion":"1","projectId":"p",
        "future":true,"capabilities":{"futureCapability":true}
    }))
    .unwrap();
    assert!(!params.capabilities.native_merge);
    let status: MutationStatus = serde_json::from_value(json!("futureStatus")).unwrap();
    assert_eq!(status, MutationStatus::Unknown);
    for sequence in ["-1", "01", "18446744073709551616", "1e3", ""] {
        assert!(serde_json::from_value::<EventSequence>(json!(sequence)).is_err());
    }
    assert!(serde_json::from_value::<ProjectId>(json!("../other-project")).is_err());
    assert!(serde_json::from_value::<ProjectId>(json!("x".repeat(129))).is_err());
    for bad in [
        json!({"jsonrpc":"2.0","id":1,"result":null,"error":{"code":1,"message":"x"}}),
        json!({"jsonrpc":"2.0","id":1}),
        json!({"jsonrpc":"1.0","id":1,"result":null}),
        json!({"jsonrpc":"2.0","id":9007199254740992i64,"result":null}),
    ] {
        assert!(serde_json::from_value::<Response>(bad).is_err());
    }
}
