use crate::board::{Board, Color, BOARD_SIZE};

pub(crate) fn find_immediate_win(
    board: &Board,
    moves: &[(usize, usize)],
    player: Color,
) -> Option<(usize, usize)> {
    let mut next = board.clone();
    for &(row, col) in moves {
        if next.place(row, col, player) {
            let win = next.check_win(row, col, player);
            next.unplace(row, col);
            if win {
                return Some((row, col));
            }
        }
    }
    None
}

pub(crate) fn find_must_block(
    board: &Board,
    moves: &[(usize, usize)],
    player: Color,
) -> Option<(usize, usize)> {
    find_immediate_win(board, moves, player.opponent())
}

pub(crate) fn is_real_four_threat(board: &Board, row: usize, col: usize, color: Color) -> bool {
    let dirs = [(0i32, 1i32), (1, 0), (1, 1), (1, -1)];
    for &(dr, dc) in &dirs {
        let mut count = 1i32;

        let (mut r, mut c) = (row as i32 + dr, col as i32 + dc);
        while r >= 0 && r < BOARD_SIZE as i32 && c >= 0 && c < BOARD_SIZE as i32
            && board.get(r as usize, c as usize) == color
        {
            count += 1;
            r += dr;
            c += dc;
        }
        let right_open = r >= 0 && r < BOARD_SIZE as i32
            && c >= 0 && c < BOARD_SIZE as i32
            && board.get(r as usize, c as usize) == Color::Empty;

        let (mut r, mut c) = (row as i32 - dr, col as i32 - dc);
        while r >= 0 && r < BOARD_SIZE as i32 && c >= 0 && c < BOARD_SIZE as i32
            && board.get(r as usize, c as usize) == color
        {
            count += 1;
            r -= dr;
            c -= dc;
        }
        let left_open = r >= 0 && r < BOARD_SIZE as i32
            && c >= 0 && c < BOARD_SIZE as i32
            && board.get(r as usize, c as usize) == Color::Empty;

        if count >= 4 && (left_open || right_open) {
            return true;
        }
    }
    false
}