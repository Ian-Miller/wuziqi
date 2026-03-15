/// 15x15 五子棋棋盘
pub const BOARD_SIZE: usize = 15;

/// 棋子颜色
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Color {
    Empty = 0,
    Black = 1,
    White = 2,
}

impl Color {
    pub fn opponent(self) -> Self {
        match self {
            Color::Black => Color::White,
            Color::White => Color::Black,
            Color::Empty => Color::Empty,
        }
    }
}

/// 棋盘状态
#[derive(Clone)]
pub struct Board {
    pub cells: [[Color; BOARD_SIZE]; BOARD_SIZE],
    pub move_count: usize,
}

impl Board {
    pub fn new() -> Self {
        Self {
            cells: [[Color::Empty; BOARD_SIZE]; BOARD_SIZE],
            move_count: 0,
        }
    }

    /// 从字节数组创建棋盘（JNI 传递）
    pub fn from_bytes(data: &[u8]) -> Self {
        let mut board = Self::new();
        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                board.cells[r][c] = match data.get(r * BOARD_SIZE + c) {
                    Some(&1) => Color::Black,
                    Some(&2) => Color::White,
                    _ => Color::Empty,
                };
            }
        }
        // 计算步数
        board.move_count = board.cells.iter()
            .flat_map(|row| row.iter())
            .filter(|&&c| c != Color::Empty)
            .count();
        board
    }

    /// 落子
    pub fn place(&mut self, row: usize, col: usize, color: Color) -> bool {
        if row >= BOARD_SIZE || col >= BOARD_SIZE || self.cells[row][col] != Color::Empty {
            return false;
        }
        self.cells[row][col] = color;
        self.move_count += 1;
        true
    }

    /// 撤销落子（供搜索回溯使用）
    pub fn unplace(&mut self, row: usize, col: usize) -> bool {
        if row >= BOARD_SIZE || col >= BOARD_SIZE || self.cells[row][col] == Color::Empty {
            return false;
        }
        self.cells[row][col] = Color::Empty;
        self.move_count = self.move_count.saturating_sub(1);
        true
    }

    /// 检查是否为空
    pub fn is_empty(&self, row: usize, col: usize) -> bool {
        row < BOARD_SIZE && col < BOARD_SIZE && self.cells[row][col] == Color::Empty
    }

    /// 获取颜色
    pub fn get(&self, row: usize, col: usize) -> Color {
        if row < BOARD_SIZE && col < BOARD_SIZE {
            self.cells[row][col]
        } else {
            Color::Empty
        }
    }

    /// 检查胜负
    pub fn check_win(&self, row: usize, col: usize, color: Color) -> bool {
        let dirs = [(0, 1), (1, 0), (1, 1), (1, -1)];
        
        for (dr, dc) in dirs {
            let mut count = 1;
            
            // 正向
            let (mut r, mut c) = (row as i32 + dr, col as i32 + dc);
            while self.in_bounds(r, c) && self.cells[r as usize][c as usize] == color {
                count += 1;
                r += dr;
                c += dc;
            }
            
            // 反向
            let (mut r, mut c) = (row as i32 - dr, col as i32 - dc);
            while self.in_bounds(r, c) && self.cells[r as usize][c as usize] == color {
                count += 1;
                r -= dr;
                c -= dc;
            }
            
            if count >= 5 {
                return true;
            }
        }
        
        false
    }

    /// 生成候选走法（邻近已有棋子的空位）
    pub fn generate_moves(&self) -> Vec<(usize, usize)> {
        let mut moves = Vec::new();
        let mut marked = [[false; BOARD_SIZE]; BOARD_SIZE];
        
        // 如果棋盘为空，返回中心点
        if self.move_count == 0 {
            moves.push((7, 7));
            return moves;
        }
        
        // 遍历所有棋子，标记周围空位
        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                if self.cells[r][c] != Color::Empty {
                    // 标记周围2格范围内的空位
                    for dr in -2..=2 {
                        for dc in -2..=2 {
                            let nr = r as i32 + dr;
                            let nc = c as i32 + dc;
                            if self.in_bounds(nr, nc) {
                                let (nr, nc) = (nr as usize, nc as usize);
                                if self.cells[nr][nc] == Color::Empty && !marked[nr][nc] {
                                    marked[nr][nc] = true;
                                    moves.push((nr, nc));
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // 如果没有候选（极少见），返回所有空位
        if moves.is_empty() {
            for r in 0..BOARD_SIZE {
                for c in 0..BOARD_SIZE {
                    if self.cells[r][c] == Color::Empty {
                        moves.push((r, c));
                    }
                }
            }
        }
        
        moves
    }

    fn in_bounds(&self, r: i32, c: i32) -> bool {
        r >= 0 && r < BOARD_SIZE as i32 && c >= 0 && c < BOARD_SIZE as i32
    }
}
