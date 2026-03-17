/// 置换表条目类型（节点类型）
#[derive(Clone, Copy, PartialEq)]
pub(super) enum TtFlag {
    Exact,
    LowerBound,
    UpperBound,
}

/// 置换表条目
#[derive(Clone, Copy)]
struct TtEntry {
    key: u64,
    depth: i32,
    score: i32,
    best_move: Option<(usize, usize)>,
    flag: TtFlag,
}

#[derive(Clone, Copy, Debug, Default)]
pub(super) struct TtStats {
    pub capacity: usize,
    pub occupied: usize,
    pub collision_replacements: u64,
    pub same_key_updates: u64,
    pub depth_rejects: u64,
}

/// 置换表大小（2^N 个条目，用位与取模）
/// 每个条目约 24 字节，2^18 = 262144 条目 ≈ 6MB
const TT_SIZE: usize = 1 << 18;
const TT_WAYS: usize = 2;
const TT_SET_COUNT: usize = TT_SIZE / TT_WAYS;
const TT_MASK: usize = TT_SET_COUNT - 1;

pub(super) struct TranspositionTable {
    entries: Vec<[Option<TtEntry>; TT_WAYS]>,
    occupied: usize,
    collision_replacements: u64,
    same_key_updates: u64,
    depth_rejects: u64,
}

impl TranspositionTable {
    pub(super) fn new() -> Self {
        Self {
            entries: vec![[None; TT_WAYS]; TT_SET_COUNT],
            occupied: 0,
            collision_replacements: 0,
            same_key_updates: 0,
            depth_rejects: 0,
        }
    }

    pub(super) fn clear(&mut self) {
        for set in &mut self.entries {
            *set = [None; TT_WAYS];
        }
        self.occupied = 0;
        self.collision_replacements = 0;
        self.same_key_updates = 0;
        self.depth_rejects = 0;
    }

    pub(super) fn get(&self, key: u64) -> Option<(i32, i32, Option<(usize, usize)>, TtFlag)> {
        let idx = (key as usize) & TT_MASK;
        self.entries[idx]
            .iter()
            .flatten()
            .filter(|e| e.key == key)
            .max_by_key(|e| e.depth)
            .map(|e| (e.depth, e.score, e.best_move, e.flag))
    }

    pub(super) fn set(
        &mut self,
        key: u64,
        depth: i32,
        score: i32,
        best_move: Option<(usize, usize)>,
        flag: TtFlag,
    ) {
        let idx = (key as usize) & TT_MASK;
        let set = &mut self.entries[idx];

        for slot in set.iter_mut() {
            if let Some(existing) = slot {
                if existing.key == key {
                    if existing.depth > depth {
                        self.depth_rejects += 1;
                        return;
                    }
                    self.same_key_updates += 1;
                    *slot = Some(TtEntry {
                        key,
                        depth,
                        score,
                        best_move,
                        flag,
                    });
                    return;
                }
            }
        }

        if let Some(empty_slot) = set.iter_mut().find(|slot| slot.is_none()) {
            self.occupied += 1;
            *empty_slot = Some(TtEntry {
                key,
                depth,
                score,
                best_move,
                flag,
            });
            return;
        }

        let victim_index = set
            .iter()
            .enumerate()
            .min_by_key(|(_, entry)| entry.as_ref().map(|e| e.depth).unwrap_or(i32::MAX))
            .map(|(index, _)| index)
            .unwrap_or(0);
        self.collision_replacements += 1;
        set[victim_index] = Some(TtEntry {
            key,
            depth,
            score,
            best_move,
            flag,
        });
    }

    pub(super) fn stats(&self) -> TtStats {
        TtStats {
            capacity: self.entries.len(),
            occupied: self.occupied,
            collision_replacements: self.collision_replacements,
            same_key_updates: self.same_key_updates,
            depth_rejects: self.depth_rejects,
        }
    }
}
