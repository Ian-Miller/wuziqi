use super::*;
use super::fixtures::{
    transcript_board_21,
    transcript_board_24,
    transcript_board_26,
    transcript_board_27_new_issue,
};

#[test]
fn master_white_prefers_inner_endpoint_on_transcript_diagonal_threat() {
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::White,
    });

    let mv = ai.take_turn(&transcript_board_21()).unwrap();
    assert_eq!(mv, (8, 5), "White should prefer the denser endpoint on the transcript diagonal threat, got {mv:?}");
}

#[test]
fn master_white_finds_better_second_defense_than_transcript() {
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::White,
    });

    let mv = ai.take_turn(&transcript_board_24()).unwrap();
    assert_ne!(mv, (7, 4), "MASTER should avoid the losing transcript defense (7,4)");
    assert_eq!(mv, (9, 5), "unexpected second defense: {mv:?}");
}

#[test]
fn master_white_finds_better_third_defense_than_transcript() {
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::White,
    });

    let mv = ai.take_turn(&transcript_board_26()).unwrap();
    assert_ne!(mv, (7, 5), "MASTER should avoid the losing transcript follow-up (7,5)");
    assert_eq!(mv, (7, 3), "unexpected third defense: {mv:?}");
}

#[test]
fn master_white_blocks_transcript_live_three_after_black_7_5() {
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::White,
    });

    let mv = ai.take_turn(&transcript_board_27_new_issue()).unwrap();
    assert_ne!(mv, (4, 8), "MASTER should not chase the fake-four point (4,8)");
    assert!(mv == (5, 7) || mv == (9, 3), "MASTER should block one live-three endpoint, got {mv:?}");
}
