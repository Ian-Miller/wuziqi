use super::*;

pub(super) fn board_with_moves(moves: &[(usize, usize, Color)]) -> Board {
    let mut board = Board::new();
    for &(row, col, color) in moves {
        assert!(board.place(row, col, color));
    }
    board
}

pub(super) fn immediate_black_win_board() -> Board {
    board_with_moves(&[
        (7, 5, Color::Black),
        (7, 6, Color::Black),
        (7, 7, Color::Black),
        (7, 8, Color::Black),
        (6, 6, Color::White),
        (8, 8, Color::White),
    ])
}

pub(super) fn must_block_white_win_board() -> Board {
    board_with_moves(&[
        (7, 5, Color::White),
        (7, 6, Color::White),
        (7, 7, Color::White),
        (7, 8, Color::White),
        (6, 6, Color::Black),
        (8, 8, Color::Black),
    ])
}

pub(super) fn forced_combo_board() -> Board {
    board_with_moves(&[
        (7, 5, Color::Black),
        (7, 6, Color::Black),
        (7, 8, Color::Black),
        (5, 7, Color::Black),
        (6, 7, Color::Black),
        (8, 7, Color::Black),
        (4, 7, Color::White),
        (10, 10, Color::White),
        (10, 11, Color::White),
    ])
}

pub(super) fn transcript_board_21() -> Board {
    board_with_moves(&[
        (7, 7, Color::Black),
        (7, 6, Color::White),
        (6, 7, Color::Black),
        (8, 7, Color::White),
        (6, 5, Color::Black),
        (9, 8, Color::White),
        (6, 6, Color::Black),
        (10, 9, Color::White),
        (11, 10, Color::Black),
        (6, 4, Color::White),
        (6, 8, Color::Black),
        (6, 9, Color::White),
        (8, 9, Color::Black),
        (7, 8, Color::White),
        (9, 6, Color::Black),
        (5, 10, Color::White),
        (4, 11, Color::Black),
        (10, 8, Color::White),
        (10, 7, Color::Black),
        (8, 8, Color::White),
        (11, 8, Color::Black),
    ])
}

pub(super) fn transcript_board_24() -> Board {
    board_with_moves(&[
        (7, 7, Color::Black),
        (7, 6, Color::White),
        (6, 7, Color::Black),
        (8, 7, Color::White),
        (6, 5, Color::Black),
        (9, 8, Color::White),
        (6, 6, Color::Black),
        (10, 9, Color::White),
        (11, 10, Color::Black),
        (6, 4, Color::White),
        (6, 8, Color::Black),
        (6, 9, Color::White),
        (8, 9, Color::Black),
        (7, 8, Color::White),
        (9, 6, Color::Black),
        (5, 10, Color::White),
        (4, 11, Color::Black),
        (10, 8, Color::White),
        (10, 7, Color::Black),
        (8, 8, Color::White),
        (11, 8, Color::Black),
        (12, 9, Color::White),
        (8, 5, Color::Black),
        (7, 4, Color::White),
    ])
}

pub(super) fn transcript_board_26() -> Board {
    board_with_moves(&[
        (7, 7, Color::Black),
        (7, 6, Color::White),
        (6, 7, Color::Black),
        (8, 7, Color::White),
        (6, 5, Color::Black),
        (9, 8, Color::White),
        (6, 6, Color::Black),
        (10, 9, Color::White),
        (11, 10, Color::Black),
        (6, 4, Color::White),
        (6, 8, Color::Black),
        (6, 9, Color::White),
        (8, 9, Color::Black),
        (7, 8, Color::White),
        (9, 6, Color::Black),
        (5, 10, Color::White),
        (4, 11, Color::Black),
        (10, 8, Color::White),
        (10, 7, Color::Black),
        (8, 8, Color::White),
        (11, 8, Color::Black),
        (12, 9, Color::White),
        (8, 5, Color::Black),
        (7, 4, Color::White),
        (9, 5, Color::Black),
        (7, 5, Color::White),
    ])
}

pub(super) fn transcript_board_27() -> Board {
    board_with_moves(&[
        (7, 7, Color::Black),
        (7, 6, Color::White),
        (6, 7, Color::Black),
        (8, 7, Color::White),
        (6, 5, Color::Black),
        (9, 8, Color::White),
        (6, 6, Color::Black),
        (10, 9, Color::White),
        (11, 10, Color::Black),
        (6, 4, Color::White),
        (6, 8, Color::Black),
        (6, 9, Color::White),
        (8, 9, Color::Black),
        (7, 8, Color::White),
        (9, 6, Color::Black),
        (5, 10, Color::White),
        (4, 11, Color::Black),
        (10, 8, Color::White),
        (10, 7, Color::Black),
        (8, 8, Color::White),
        (11, 8, Color::Black),
        (8, 5, Color::White),
        (12, 9, Color::Black),
        (13, 10, Color::White),
        (8, 4, Color::Black),
        (9, 7, Color::White),
        (7, 5, Color::Black),
    ])
}

pub(super) fn ai_vs_ai_degrade_open_three_board() -> Board {
    board_with_moves(&[
        (7, 7, Color::Black),
        (7, 6, Color::White),
        (6, 7, Color::Black),
        (8, 7, Color::White),
        (6, 5, Color::Black),
        (9, 8, Color::White),
        (6, 6, Color::Black),
        (10, 9, Color::White),
        (11, 10, Color::Black),
        (6, 4, Color::White),
        (6, 8, Color::Black),
        (6, 9, Color::White),
        (8, 9, Color::Black),
        (7, 8, Color::White),
        (9, 6, Color::Black),
        (5, 10, Color::White),
        (4, 11, Color::Black),
        (10, 8, Color::White),
        (10, 7, Color::Black),
        (8, 8, Color::White),
        (11, 8, Color::Black),
        (8, 5, Color::White),
        (12, 9, Color::Black),
        (13, 10, Color::White),
        (8, 4, Color::Black),
        (9, 7, Color::White),
        (7, 5, Color::Black),
    ])
}

pub(super) fn ai_vs_ai_degrade_second_defense_board() -> Board {
    transcript_board_24()
}
