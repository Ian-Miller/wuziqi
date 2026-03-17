use super::*;
use super::fixtures::{
    ai_vs_ai_degrade_open_three_board,
    ai_vs_ai_degrade_second_defense_board,
    forced_combo_board,
    immediate_black_win_board,
    must_block_white_win_board,
    transcript_board_21,
    transcript_board_24,
    transcript_board_26,
    transcript_board_27,
};

#[test]
fn minimax_shared_regression_immediate_win() {
    let board = immediate_black_win_board();
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 12,
        time_limit_ms: 4_000,
        player: Color::Black,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert!(matches!(mv, (7, 4) | (7, 9)), "unexpected minimax win move: {mv:?}");
}

#[test]
fn mcts_shared_regression_immediate_win() {
    let board = immediate_black_win_board();
    let mut ai = MctsAi::new(MctsConfig {
        player: Color::Black,
        time_limit_ms: 1_800,
        exploration_c: 1.2,
        max_children: 12,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert!(matches!(mv, (7, 4) | (7, 9)), "unexpected mcts win move: {mv:?}");
}

#[test]
fn minimax_shared_regression_must_block() {
    let board = must_block_white_win_board();
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 12,
        time_limit_ms: 4_000,
        player: Color::Black,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert!(matches!(mv, (7, 4) | (7, 9)), "unexpected minimax block move: {mv:?}");
}

#[test]
fn mcts_shared_regression_must_block() {
    let board = must_block_white_win_board();
    let mut ai = MctsAi::new(MctsConfig {
        player: Color::Black,
        time_limit_ms: 1_800,
        exploration_c: 1.2,
        max_children: 12,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert!(matches!(mv, (7, 4) | (7, 9)), "unexpected mcts block move: {mv:?}");
}

#[test]
fn minimax_shared_regression_forced_combo() {
    let board = forced_combo_board();
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::Black,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (7, 7));
}

#[test]
fn mcts_shared_regression_forced_combo() {
    let board = forced_combo_board();
    let mut ai = MctsAi::new(MctsConfig {
        player: Color::Black,
        time_limit_ms: 1_800,
        exploration_c: 1.2,
        max_children: 12,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (7, 7));
}

#[test]
fn minimax_shared_regression_transcript_diagonal_endpoint() {
    let board = transcript_board_21();
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::White,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (8, 5));
}

#[test]
fn minimax_shared_regression_transcript_second_defense() {
    let board = transcript_board_24();
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::White,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (9, 5));
}

#[test]
fn minimax_shared_regression_transcript_third_defense() {
    let board = transcript_board_26();
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::White,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (7, 3));
}

#[test]
fn minimax_shared_regression_ai_vs_ai_open_three_block() {
    let board = ai_vs_ai_degrade_open_three_board();
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::White,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert!(matches!(mv, (5, 7) | (9, 3)), "unexpected minimax degradation defense: {mv:?}");
}

#[test]
fn minimax_shared_regression_ai_vs_ai_second_defense() {
    let board = ai_vs_ai_degrade_second_defense_board();
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::White,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (9, 5));
}

#[test]
fn mcts_shared_regression_transcript_diagonal_endpoint() {
    let board = transcript_board_21();
    let mut ai = MctsAi::new(MctsConfig {
        player: Color::White,
        time_limit_ms: 1_800,
        exploration_c: 1.2,
        max_children: 12,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (12, 9));
}

#[test]
fn mcts_shared_regression_transcript_second_defense() {
    let board = transcript_board_24();
    let mut ai = MctsAi::new(MctsConfig {
        player: Color::White,
        time_limit_ms: 1_800,
        exploration_c: 1.2,
        max_children: 12,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (8, 4));
}

#[test]
fn mcts_shared_regression_transcript_third_defense() {
    let board = transcript_board_26();
    let mut ai = MctsAi::new(MctsConfig {
        player: Color::White,
        time_limit_ms: 1_800,
        exploration_c: 1.2,
        max_children: 12,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (7, 3));
}

#[test]
fn mcts_shared_regression_ai_vs_ai_open_three_block() {
    let board = ai_vs_ai_degrade_open_three_board();
    let mut ai = MctsAi::new(MctsConfig {
        player: Color::White,
        time_limit_ms: 1_800,
        exploration_c: 1.2,
        max_children: 12,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (5, 7));
}

#[test]
fn mcts_shared_regression_ai_vs_ai_second_defense() {
    let board = ai_vs_ai_degrade_second_defense_board();
    let mut ai = MctsAi::new(MctsConfig {
        player: Color::White,
        time_limit_ms: 1_800,
        exploration_c: 1.2,
        max_children: 12,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (8, 4));
}

#[test]
fn mcts_shared_regression_transcript_live_three_block() {
    let board = transcript_board_27();
    let mut ai = MctsAi::new(MctsConfig {
        player: Color::White,
        time_limit_ms: 1_800,
        exploration_c: 1.2,
        max_children: 12,
    });
    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (5, 7));
}
