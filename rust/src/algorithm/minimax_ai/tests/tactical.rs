use super::*;
use super::fixtures::board_with_moves;

#[test]
fn evaluate_position_keeps_four_combo_bonus() {
    let pure_open_four = board_with_moves(&[
        (7, 5, Color::Black),
        (7, 6, Color::Black),
        (7, 8, Color::Black),
    ]);
    let combo_board = board_with_moves(&[
        (7, 5, Color::Black),
        (7, 6, Color::Black),
        (7, 8, Color::Black),
        (5, 7, Color::Black),
        (6, 7, Color::Black),
        (8, 7, Color::Black),
        (4, 7, Color::White),
    ]);

    let pure_score = evaluate_position(&pure_open_four, 7, 7, Color::Black);
    let combo_score = evaluate_position(&combo_board, 7, 7, Color::Black);
    assert!(combo_score > pure_score, "pure={pure_score}, combo={combo_score}");
}

#[test]
fn heuristic_score_prioritizes_strong_defense() {
    let board = board_with_moves(&[
        (7, 7, Color::White),
        (7, 8, Color::White),
        (7, 9, Color::White),
        (6, 6, Color::Black),
        (8, 8, Color::Black),
    ]);
    let ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 12,
        time_limit_ms: 4_000,
        player: Color::Black,
    });

    let defend = ai.heuristic_score(&board, 7, 10);
    let attack = ai.heuristic_score(&board, 6, 8);
    assert!(defend > attack, "defend={defend}, attack={attack}");
}

#[test]
fn hard_ai_blocks_open_three_extension() {
    let board = board_with_moves(&[
        (7, 7, Color::White),
        (7, 8, Color::White),
        (7, 9, Color::White),
        (6, 6, Color::Black),
        (8, 8, Color::Black),
        (9, 9, Color::Black),
    ]);
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 12,
        time_limit_ms: 4_000,
        player: Color::Black,
    });

    let mv = ai.take_turn(&board).unwrap();
    assert!(mv == (7, 6) || mv == (7, 10), "unexpected move: {mv:?}");
}

#[test]
fn hard_ai_blocks_single_blocked_four_threat() {
    let board = board_with_moves(&[
        (7, 5, Color::Black),
        (7, 6, Color::White),
        (7, 7, Color::White),
        (7, 8, Color::White),
        (6, 6, Color::Black),
        (8, 8, Color::Black),
    ]);
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 12,
        time_limit_ms: 4_000,
        player: Color::Black,
    });

    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (7, 9));
}

#[test]
fn opening_keeps_mild_defense_discount() {
    let board = board_with_moves(&[
        (7, 7, Color::White),
        (7, 8, Color::White),
        (6, 6, Color::Black),
        (6, 7, Color::Black),
    ]);
    let ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::Black,
    });

    let attack_score = ai.move_tactical_score(&board, 6, 8, Color::Black);
    let defend_score = ai.move_tactical_score(&board, 7, 9, Color::Black);
    assert!(attack_score >= defend_score, "attack={attack_score}, defend={defend_score}");
}

#[test]
fn master_ai_prefers_compound_forcing_attack() {
    let board = board_with_moves(&[
        (7, 5, Color::Black),
        (7, 6, Color::Black),
        (7, 8, Color::Black),
        (5, 7, Color::Black),
        (6, 7, Color::Black),
        (8, 7, Color::Black),
        (4, 7, Color::White),
        (10, 10, Color::White),
        (10, 11, Color::White),
    ]);
    let mut ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::Black,
    });

    let mv = ai.take_turn(&board).unwrap();
    assert_eq!(mv, (7, 7));
}

#[test]
fn critical_block_prefers_denser_contested_endpoint() {
    let board = board_with_moves(&[
        (8, 6, Color::Black),
        (8, 7, Color::Black),
        (8, 8, Color::Black),
        (7, 5, Color::White),
        (7, 6, Color::White),
        (9, 5, Color::White),
        (9, 6, Color::White),
    ]);
    let ai = MinimaxAi::new(MinimaxConfig {
        max_depth: 20,
        time_limit_ms: 8_000,
        player: Color::White,
    });

    let left_score = evaluate_position(&board, 8, 5, Color::Black);
    let right_score = evaluate_position(&board, 8, 9, Color::Black);
    let left_density = ai.local_control_density(&board, 8, 5);
    let right_density = ai.local_control_density(&board, 8, 9);

    let moves = board.generate_moves();
    let best_block = ai.find_critical_block(&board, &moves);
    assert_eq!(left_score, right_score, "left_score={left_score}, right_score={right_score}");
    assert!(left_density > right_density, "left_density={left_density}, right_density={right_density}");
    assert_eq!(best_block, Some((8, 5)), "left_density={left_density}, right_density={right_density}, best={best_block:?}");
}
