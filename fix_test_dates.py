import re
import datetime

with open('app/src/test/kotlin/com/minibrain/ai/agent/PlannerHintBuilderTest.kt', 'r') as f:
    content = f.read()

# The DateResolver uses LocalDate.now() which means tests checking for "2023年5月15日の日記"
# get interpreted relative to today (e.g. 2026).
# We should mock LocalDate.now() or just use absolute dates that map to the same year,
# or just change the assertions to match 2026.
# Let's fix the test by using a date string that will ALWAYS parse exactly, like "2023年5月15日の日記"
# Actually, DateResolver might map "2023年5月15日" to 2023-05-15 regardless of today, UNLESS it's just "5月15日".
# But wait, in the hint it resolved to 2026-05-15 because "2023年" wasn't parsed correctly?
# Let's check `DateResolverTest.kt` or `DateResolver.kt` to see how it handles "2023年5月15日".
