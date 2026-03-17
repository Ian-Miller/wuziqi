use super::*;

impl MctsAi {
    pub(super) fn opening_book(&mut self, board: &Board) -> Option<(usize, usize)> {
        const CENTER: (usize, usize) = (7, 7);

        match board.move_count {
            0 => Some(CENTER),
            1 => {
                if board.is_empty(CENTER.0, CENTER.1) {
                    return Some(CENTER);
                }

                let standard = [
                    (6, 7), (8, 7), (7, 6), (7, 8),
                    (6, 6), (8, 8), (6, 8), (8, 6),
                ];

                if self.easy_mode {
                    let novice = [
                        (5, 7), (9, 7), (7, 5), (7, 9),
                        (5, 5), (9, 9), (5, 9), (9, 5),
                        (6, 9), (9, 6), (8, 5), (5, 8),
                    ];
                    if self.roll(0.50) {
                        if let Some(m) = self.pick_from_available(board, &novice) {
                            return Some(m);
                        }
                    }
                    if let Some(m) = self.pick_from_available(board, &standard) {
                        return Some(m);
                    }
                }

                standard.iter().find(|&&p| board.is_empty(p.0, p.1)).copied()
            }
            2 => {
                let near = [
                    (7, 7), (6, 7), (8, 7), (7, 6), (7, 8),
                    (6, 6), (8, 8), (6, 8), (8, 6),
                ];

                if self.easy_mode {
                    let novice_wide = [
                        (5, 6), (5, 8), (6, 5), (8, 5),
                        (9, 6), (9, 8), (6, 9), (8, 9),
                        (4, 7), (10, 7), (7, 4), (7, 10),
                    ];
                    if self.roll(0.55) {
                        if let Some(m) = self.pick_from_available(board, &novice_wide) {
                            return Some(m);
                        }
                    }
                    if let Some(m) = self.pick_from_available(board, &near) {
                        return Some(m);
                    }
                }

                near.iter().find(|&&p| board.is_empty(p.0, p.1)).copied()
            }
            _ => None,
        }
    }

    fn pick_from_available(&mut self, board: &Board, candidates: &[(usize, usize)]) -> Option<(usize, usize)> {
        let available: Vec<(usize, usize)> = candidates
            .iter()
            .copied()
            .filter(|&(r, c)| board.is_empty(r, c))
            .collect();

        if available.is_empty() {
            None
        } else {
            let idx = (self.rand_u64() as usize) % available.len();
            Some(available[idx])
        }
    }

    pub(super) fn sample_softmax(&mut self, items: &[((usize, usize), u32)], temp: f64) -> Option<(usize, usize)> {
        if items.is_empty() {
            return None;
        }
        if items.len() == 1 {
            return Some(items[0].0);
        }

        let t = temp.max(0.05);
        let max_v = items.iter().map(|(_, v)| *v as f64).fold(f64::MIN, f64::max);
        let mut weights = Vec::with_capacity(items.len());
        let mut sum = 0.0;

        for (_, v) in items {
            let w = ((*v as f64 - max_v) / t).exp();
            weights.push(w);
            sum += w;
        }

        if sum <= f64::EPSILON {
            return Some(items[0].0);
        }

        let mut r = self.rand_f64() * sum;
        for (idx, w) in weights.iter().enumerate() {
            r -= *w;
            if r <= 0.0 {
                return Some(items[idx].0);
            }
        }
        Some(items[items.len() - 1].0)
    }
}