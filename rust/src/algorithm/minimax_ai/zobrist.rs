use super::*;

/// Zobrist 哈希表（每格 × 颜色 的随机数）
pub(super) struct ZobristTable {
    table: [[[u64; 2]; BOARD_SIZE]; BOARD_SIZE],
}

impl ZobristTable {
    pub(super) fn new() -> Self {
        let mut state: u64 = 0x_deadbeef_cafef00d;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };

        let mut table = [[[0u64; 2]; BOARD_SIZE]; BOARD_SIZE];
        for row in &mut table {
            for col in row {
                col[0] = next();
                col[1] = next();
            }
        }
        Self { table }
    }

    pub(super) fn hash_board(&self, board: &Board) -> u64 {
        let mut h: u64 = 0;
        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                match board.get(r, c) {
                    Color::Black => h ^= self.table[r][c][0],
                    Color::White => h ^= self.table[r][c][1],
                    Color::Empty => {}
                }
            }
        }
        h
    }

    pub(super) fn hash_move(&self, hash: u64, row: usize, col: usize, color: Color) -> u64 {
        let color_idx = if color == Color::Black { 0 } else { 1 };
        hash ^ self.table[row][col][color_idx]
    }
}
