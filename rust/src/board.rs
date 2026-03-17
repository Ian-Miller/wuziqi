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

#[derive(Clone, Copy, Default)]
struct MoveGenProfile {
    max_chain: i32,
    strong_lines: i32,
    contested_stones: i32,
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

    fn mark_candidate(
        &self,
        moves: &mut Vec<(usize, usize)>,
        marked: &mut [[bool; BOARD_SIZE]; BOARD_SIZE],
        row: i32,
        col: i32,
    ) {
        if !self.in_bounds(row, col) {
            return;
        }
        let (row, col) = (row as usize, col as usize);
        if self.cells[row][col] == Color::Empty && !marked[row][col] {
            marked[row][col] = true;
            moves.push((row, col));
        }
    }

    fn add_radius_candidates(
        &self,
        moves: &mut Vec<(usize, usize)>,
        marked: &mut [[bool; BOARD_SIZE]; BOARD_SIZE],
        radius: i32,
    ) {
        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                if self.cells[r][c] == Color::Empty {
                    continue;
                }
                for dr in -radius..=radius {
                    for dc in -radius..=radius {
                        self.mark_candidate(moves, marked, r as i32 + dr, c as i32 + dc);
                    }
                }
            }
        }
    }

    fn add_line_extension_candidates(
        &self,
        moves: &mut Vec<(usize, usize)>,
        marked: &mut [[bool; BOARD_SIZE]; BOARD_SIZE],
        extension_steps: i32,
    ) {
        let dirs = [(1i32, 0i32), (0, 1), (1, 1), (1, -1)];

        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                let color = self.cells[r][c];
                if color == Color::Empty {
                    continue;
                }

                for &(dr, dc) in &dirs {
                    let pr = r as i32 - dr;
                    let pc = c as i32 - dc;
                    if self.in_bounds(pr, pc) && self.cells[pr as usize][pc as usize] == color {
                        continue;
                    }

                    let mut len = 0i32;
                    let mut rr = r as i32;
                    let mut cc = c as i32;
                    while self.in_bounds(rr, cc) && self.cells[rr as usize][cc as usize] == color {
                        len += 1;
                        rr += dr;
                        cc += dc;
                    }

                    if len < 2 {
                        continue;
                    }

                    let left_r = r as i32 - dr;
                    let left_c = c as i32 - dc;
                    for step in 0..=extension_steps {
                        self.mark_candidate(moves, marked, left_r - step * dr, left_c - step * dc);
                        self.mark_candidate(moves, marked, rr + step * dr, cc + step * dc);
                    }
                }
            }
        }
    }

    fn add_hotspot_candidates(
        &self,
        moves: &mut Vec<(usize, usize)>,
        marked: &mut [[bool; BOARD_SIZE]; BOARD_SIZE],
        radius: i32,
    ) {
        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                let color = self.cells[r][c];
                if color == Color::Empty {
                    continue;
                }

                let mut same_near = 0i32;
                let mut opp_near = 0i32;
                for dr in -2i32..=2 {
                    for dc in -2i32..=2 {
                        if dr == 0 && dc == 0 {
                            continue;
                        }
                        let nr = r as i32 + dr;
                        let nc = c as i32 + dc;
                        if !self.in_bounds(nr, nc) {
                            continue;
                        }
                        match self.cells[nr as usize][nc as usize] {
                            x if x == color => same_near += 1,
                            Color::Empty => {}
                            _ => opp_near += 1,
                        }
                    }
                }

                if same_near == 0 || opp_near == 0 {
                    continue;
                }

                for dr in -radius..=radius {
                    for dc in -radius..=radius {
                        self.mark_candidate(moves, marked, r as i32 + dr, c as i32 + dc);
                    }
                }
            }
        }
    }

    fn movegen_profile(&self) -> MoveGenProfile {
        let dirs = [(1i32, 0i32), (0, 1), (1, 1), (1, -1)];
        let mut profile = MoveGenProfile::default();

        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                let color = self.cells[r][c];
                if color == Color::Empty {
                    continue;
                }

                let mut same_near = 0i32;
                let mut opp_near = 0i32;
                for dr in -2i32..=2 {
                    for dc in -2i32..=2 {
                        if dr == 0 && dc == 0 {
                            continue;
                        }
                        let nr = r as i32 + dr;
                        let nc = c as i32 + dc;
                        if !self.in_bounds(nr, nc) {
                            continue;
                        }
                        match self.cells[nr as usize][nc as usize] {
                            x if x == color => same_near += 1,
                            Color::Empty => {}
                            _ => opp_near += 1,
                        }
                    }
                }
                if same_near > 0 && opp_near > 0 {
                    profile.contested_stones += 1;
                }

                for &(dr, dc) in &dirs {
                    let pr = r as i32 - dr;
                    let pc = c as i32 - dc;
                    if self.in_bounds(pr, pc) && self.cells[pr as usize][pc as usize] == color {
                        continue;
                    }

                    let mut len = 0i32;
                    let mut rr = r as i32;
                    let mut cc = c as i32;
                    while self.in_bounds(rr, cc) && self.cells[rr as usize][cc as usize] == color {
                        len += 1;
                        rr += dr;
                        cc += dc;
                    }

                    if len > profile.max_chain {
                        profile.max_chain = len;
                    }
                    if len >= 3 {
                        profile.strong_lines += 1;
                    }
                }
            }
        }

        profile
    }

    /// 生成候选走法（按局面阶段动态收放候选区域）
    pub fn generate_moves(&self) -> Vec<(usize, usize)> {
        let mut moves = Vec::new();
        let mut marked = [[false; BOARD_SIZE]; BOARD_SIZE];
        
        // 如果棋盘为空，返回中心点
        if self.move_count == 0 {
            moves.push((7, 7));
            return moves;
        }

        let profile = self.movegen_profile();
        let tactical_pressure = profile.max_chain >= 4 || profile.strong_lines >= 3;
        let contested = profile.contested_stones >= 6;
        let base_radius = if self.move_count <= 8 {
            2
        } else if tactical_pressure || contested {
            2
        } else if self.move_count <= 42 {
            1
        } else {
            2
        };
        let extension_steps = if profile.max_chain >= 4 || profile.strong_lines >= 2 {
            2
        } else {
            1
        };

        self.add_radius_candidates(&mut moves, &mut marked, base_radius);
        self.add_line_extension_candidates(&mut moves, &mut marked, extension_steps);

        if contested {
            self.add_hotspot_candidates(&mut moves, &mut marked, 1);
        }

        if self.move_count <= 16 || moves.len() < 14 || tactical_pressure {
            self.add_radius_candidates(&mut moves, &mut marked, 2);
        }

        if (self.move_count >= 36 || contested) && moves.len() < 20 {
            self.add_radius_candidates(&mut moves, &mut marked, 2);
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
