/// 叶子评估缓存（直接映射）
const EVAL_CACHE_SIZE: usize = 1 << 16;
const EVAL_CACHE_MASK: usize = EVAL_CACHE_SIZE - 1;

#[derive(Clone, Copy)]
struct EvalCacheEntry {
    key: u64,
    score: i32,
}

pub(super) struct EvalCache {
    entries: Vec<Option<EvalCacheEntry>>,
}

impl EvalCache {
    pub(super) fn new() -> Self {
        Self {
            entries: vec![None; EVAL_CACHE_SIZE],
        }
    }

    pub(super) fn clear(&mut self) {
        for e in &mut self.entries {
            *e = None;
        }
    }

    pub(super) fn get(&self, key: u64) -> Option<i32> {
        let idx = (key as usize) & EVAL_CACHE_MASK;
        self.entries[idx]
            .as_ref()
            .filter(|e| e.key == key)
            .map(|e| e.score)
    }

    pub(super) fn set(&mut self, key: u64, score: i32) {
        let idx = (key as usize) & EVAL_CACHE_MASK;
        self.entries[idx] = Some(EvalCacheEntry { key, score });
    }
}
