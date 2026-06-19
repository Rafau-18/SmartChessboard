// Regression tests for the reed-matrix debounce state machine
// (firmware/lib/debounce). These pin the exact commit/reset semantics of the
// hardware-verified loop in firmware/src/main.cpp so the Phase-3 extraction can
// be proven behaviour-identical: a bit commits only after kStableScans (4)
// consecutive agreeing scans, and any single disagreeing scan resets the counter.

#include <unity.h>

#include "debounce.h"

namespace db = debounce;

void setUp(void) {}
void tearDown(void) {}

static const uint64_t kSq12 = 1ULL << 12;  // e2

// A bit flips to occupied only after kStableScans consecutive agreeing scans.
static void test_commit_after_four_agreeing_scans(void) {
    db::Debouncer d;
    db::init(d, kSq12);  // board boots with sq12 occupied; stable starts at 0

    // Three agreeing scans climb the counter but do NOT yet commit.
    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, kSq12));  // agree = 1
    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, kSq12));  // agree = 2
    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, kSq12));  // agree = 3
    // The 4th agreeing scan commits the bit.
    TEST_ASSERT_EQUAL_UINT64(kSq12, db::step(d, kSq12));  // agree = 4 → commit
}

// A single disagreeing scan resets the counter (so the full window is needed again).
static void test_single_disagreement_resets_counter(void) {
    db::Debouncer d;
    db::init(d, kSq12);

    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, kSq12));  // agree = 1
    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, kSq12));  // agree = 2
    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, kSq12));  // agree = 3 (one short of commit)

    // One disagreeing scan (square reads empty) resets sq12's counter to 0.
    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, 0));  // disagree → agree = 0, rawPrev = 0

    // Re-introducing the magnet disagrees again (vs rawPrev = 0), so the counter
    // must climb from scratch — proving the reset. Had it NOT reset, the bit
    // would have committed far sooner.
    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, kSq12));  // disagree (sq12 vs 0) → agree = 0
    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, kSq12));  // agree = 1
    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, kSq12));  // agree = 2
    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, kSq12));  // agree = 3 → still not committed
    TEST_ASSERT_EQUAL_UINT64(kSq12, db::step(d, kSq12));  // agree = 4 → commit
}

// Symmetry: a committed bit clears only after kStableScans agreeing empty scans.
static void test_clear_after_four_agreeing_empty_scans(void) {
    db::Debouncer d;
    db::init(d, kSq12);
    for (int i = 0; i < 4; ++i) db::step(d, kSq12);  // commit sq12 occupied
    TEST_ASSERT_EQUAL_UINT64(kSq12, d.stable);

    // Now the square reads empty. First empty scan disagrees (vs rawPrev = sq12).
    TEST_ASSERT_EQUAL_UINT64(kSq12, db::step(d, 0));  // disagree → agree = 0, still set
    TEST_ASSERT_EQUAL_UINT64(kSq12, db::step(d, 0));  // agree = 1
    TEST_ASSERT_EQUAL_UINT64(kSq12, db::step(d, 0));  // agree = 2
    TEST_ASSERT_EQUAL_UINT64(kSq12, db::step(d, 0));  // agree = 3 (still set)
    TEST_ASSERT_EQUAL_UINT64(0, db::step(d, 0));      // agree = 4 → cleared
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_commit_after_four_agreeing_scans);
    RUN_TEST(test_single_disagreement_resets_counter);
    RUN_TEST(test_clear_after_four_agreeing_empty_scans);
    return UNITY_END();
}
