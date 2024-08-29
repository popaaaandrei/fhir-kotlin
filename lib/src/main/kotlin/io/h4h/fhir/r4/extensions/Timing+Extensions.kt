package io.h4h.fhir.r4.extensions

import io.h4h.fhir.r4.base.*
import io.h4h.fhir.r4.base.DayOfWeek
import kotlinx.datetime.*



// ==========================================================================
// TimingException
// ==========================================================================

class TimingException(message: String) : IllegalStateException(message)



// ==========================================================================
// Validation
// ==========================================================================

/**
 * When the event is to occur:
    + Rule: if there's a duration, there needs to be duration units
    + Rule: if there's a period, there needs to be period units
    + Rule: If there's a timeOfDay, there cannot be a when, or vice versa
    + Rule: duration SHALL be a non-negative value
    + Rule: period SHALL be a non-negative value
    + Rule: If there's a periodMax, there must be a period
    + Rule: If there's a durationMax, there must be a duration
    + Rule: If there's a countMax, there must be a count
    + Rule: If there's an offset, there must be a when (and not C, CM, CD, CV)
 */
fun Timing.validate() {
    // guard
    val repeat = this.repeat ?: return

    // extract these properties in a val, otherwise the smart cast for null check will not work
    val repeatPeriod = repeat.period
    val repeatDuration = repeat.duration
    val repeatWhen = repeat.`when`

    when {
        // Rule: if there's a duration, there needs to be duration units
        (repeat.duration != null && repeat.durationUnit == null)
        -> throw TimingException("Rule: if there's a duration, there needs to be duration units, and vice versa")

        // Rule: if there's a duration, there needs to be duration units
        (repeat.duration == null && repeat.durationUnit != null)
        -> throw TimingException("Rule: if there's a duration, there needs to be duration units, and vice versa")

        // Rule: duration SHALL be a non-negative value
        (repeatDuration != null && repeatDuration < 0)
        -> throw TimingException("Rule: if there's a duration, it SHALL be a non-negative value")

        // Rule: If there's a durationMax, there must be a duration
        (repeat.durationMax != null && repeat.duration == null)
        -> throw TimingException("Rule: If there's a durationMax, there must be a duration")

        // Rule: if there's a period, there needs to be period units
        (repeat.period != null && repeat.periodUnit == null)
        -> throw TimingException("Rule: if there's a period, there needs to be period units, and vice versa")

        // Rule: if there's a period, there needs to be period units
        (repeat.period == null && repeat.periodUnit != null)
        -> throw TimingException("Rule: if there's a period, there needs to be period units, and vice versa")

        // Rule: period SHALL be a non-negative value
        (repeatPeriod != null && repeatPeriod < 0)
        -> throw TimingException("Rule: if there's a period, it SHALL be a non-negative value")

        // Rule: Rule: If there's a durationMax, there must be a duration
        (repeat.periodMax != null && repeat.period == null)
        -> throw TimingException("Rule: If there's a periodMax, there must be a period")

        // Rule: If there's a timeOfDay, there cannot be a when, or vice versa
        (repeat.timeOfDay != null && repeat.`when` != null)
        -> throw TimingException("Rule: If there's a timeOfDay, there cannot be a when, or vice versa")

        // Assumption 1: frequency exists when there is some bound specified
        (repeat.frequency != null && repeat.period == null && repeat.boundsPeriod == null)
        -> throw TimingException("Assumption: frequency exists solely within some period/bounds")

        // Rule: If there's a countMax, there must be a count
        (repeat.countMax != null && repeat.count == null)
        -> throw TimingException("Rule: If there's a countMax, there must be a count")

        // Rule: If there's an offset, there must be a when (and not C, CM, CD, CV)
        (repeat.offset != null && (repeatWhen == null ||
            repeatWhen.any { it == EventTiming.C || it == EventTiming.CM || it == EventTiming.CD || it == EventTiming.CV }))
        -> throw TimingException("Rule: If there's an offset, there must be a when (and not C, CM, CD, CV)")
    }

    // fields that are not implemented
    repeat.boundsDuration?.let { throw NotImplementedError("Timing 'boundsDuration' not implemented") }
    repeat.boundsRange?.let { throw NotImplementedError("Timing 'boundsRange' not implemented") }

    // TODO: parse this.code TimingAbbreviation
    code?.let { throw NotImplementedError("Timing 'code' (TimingAbbreviation) not implemented") }
}


// ==========================================================================
// Date inclusion
// ==========================================================================

/**
 * Checks if the Timing object has a configuration that includes today
 */
fun Timing.isToday() = includesDate(LocalDate.now())


/**
 * Checks if the Timing object has a configuration that represents the date given by the parameter
 */
fun Timing.includesDate(date: LocalDate): Boolean {

    // 1) if we have events specified
    this.event?.let { eventList ->
        // parse events if there is at least one for today
        val containsToday = eventList.any { Instant.parse(it).toLocalDate().isToday() }
        if (containsToday) return true
    }

    // proceed with parsing the repeat component
    val repeat = this.repeat ?: return false

    // 2) we have 'count' but it has been depleted => task is not for today
    // if we don't have count, or it is greater than 0, the task MIGHT BE for today => we continue the algorithm
    if (repeat.count == 0) return false
    // TODO: additional check. If we have a countMax, compare with what already exists

    // 3) if we have bounds, check if the currentDate is withing the interval
    val boundsPeriod = repeat.boundsPeriod?.let {
        // if the period doesn't include today, return
        if (!it.contains(date)) {
            return false
        }
        return@let it
    }

    // 4) if we have monthsOfYear or daysOfMonth, check if the current date is within the interval and continue the algorithm if it is
    if (repeat.monthsOfYear.isNotEmpty() && !repeat.monthsOfYear.contains(date.month)) {
        return false
    }

    if (repeat.daysOfMonth.isNotEmpty() && !repeat.daysOfMonth.contains(date.dayOfMonth)){
        return false
    }

    // 5) if we have 'dayOfWeek' present but the current day is not part of the list, the task is not for today
    repeat.dayOfWeek?.let { days ->
        return days.checkDayOfWeek(date)
    }

    // 6) if we have bounds: either both 'start' and 'end', or just 'start'
    boundsPeriod?.let { period ->
        // if we have start, proceed analyzing period, frequency, bounds
        period.start?.toLocalDate()?.let { start ->
            return@includesDate repeat.checkMainRepeatConfig(start, date)
        }
    }

    return false
}


// ==========================================================================
// Frequency calculation
// ==========================================================================

/**
 * Get the number of times a specific task is handled in a day
 * E.g. frequency 3, period 6, periodUnit H => 3 times, every 6 hours => (24 / 6) * 3 = 12 times per day
 * TODO: momentan este apelat dupa isToday(), deci presupune ca azi avem un Task de facut cel putin
 */
fun Timing.getDailyFrequency(): Int {

    // if we have events specified, parse event to see how many are for today (usually one)
    this.event?.let { eventList ->
        return eventList
            // isToday?
            .filter { Instant.parse(it).toLocalDate().isToday() }
            .size
    }

    // TODO: parse this.code TimingAbbreviation
    this.code?.let { throw NotImplementedError("Timing 'code' (TimingAbbreviation) not implemented") }

    // proceed with parsing the repeat component
    val repeat = this.repeat ?: return 0

    // if today, see if we have multiple times
    repeat.timeOfDay?.let { return it.size }

    // if today and when specified, return 1 occurrence
    repeat.`when`?.let { return 1 }

    val period = repeat.period
    val periodUnit = repeat.periodUnit
    val frequency = repeat.frequency
    val bounds = repeat.boundsPeriod

    when {
        // frequency not specified
        (frequency == null) -> return 0

        // if we have all
        (period != null && periodUnit != null) -> {
            // Hourly
            if (periodUnit == UnitsOfTime.H) {
                //  24 (hours) divided by repeat.period - see how many tasks per day (per 24 hours)
                // e.g.: 3 tasks every 6 hours = (24 / 6) * 3 = 4 * 3 = 12 tasks PER DAY
                return (24 / period).toInt() * frequency
            }

            return frequency
        }

        // we have just frequency
        (bounds != null) -> return frequency
    }

    return 0
}


// ==========================================================================
// helpers
// ==========================================================================

/**
 * Checks if the current date is valid, using the following fields: period, periodUnit, periodMax, when
 */
private fun TimingRepeatComponent.checkMainRepeatConfig(
    start: LocalDate,
    currentDate: LocalDate
): Boolean {
    // either both are null or both are not null
    if (periodUnit != null && period != null) {
        return this.checkDateByPeriod(start, currentDate)
    }

    `when`?.let { `when` ->
        // each 'when' will happen at least once a day
        return `when`.isNotEmpty()
    }

    return false
}


private fun TimingRepeatComponent.checkDateByPeriod(
    start: LocalDate,
    currentDate: LocalDate
): Boolean {
    val period = this.period ?: return false
    val periodMax = this.periodMax ?: period

    when (periodUnit) {

        // Hourly
        UnitsOfTime.H -> {
            // the event will happen at least once per day
            return true
        }

        // Daily
        UnitsOfTime.D -> {
            // Each day
            if (period == 1.0) {
                return true
            }

            // Each 'repeat.period' days
            if (period > 1.0) {
                return currentDate.isValid(start, period.toInt(), periodMax)
            }
        }

        // Weekly
        UnitsOfTime.WK -> {
            // Each week
            if (period == 1.0) {
                return currentDate.isValid(start, 7, periodMax * 7)
            }

            // Each 'repeat.period' weeks
            if (period > 1.0) {
                return currentDate.isValid(start, period.toInt() * 7, periodMax * 7)
            }
        }

        // Monthly, Yearly
        UnitsOfTime.MO, UnitsOfTime.A -> {
            return true
            //TODO: handle "every 2nd month/year, 3rd month/year, 6th month/year, etc cases - if we are going to have such schedules"
        }

        else -> return false
    }

    return false
}



/**
 * @return true if the current date is a valid date in the interval. E.g. each 3rd day (the 'periodOfDays' param) between 'start' and 'end'
 */
private fun LocalDate.isValid(
    start: LocalDate,
    periodOfDays: Int,
    maxPeriodOfDays: Double
): Boolean {
    // get the number of days between today and period.start
    val diff = start.daysUntil(this)

    // guard
    // if (diff < 0) return false

    for (numberOfDays in periodOfDays..maxPeriodOfDays.toInt()) {
        if (diff % numberOfDays == 0) return true
    }

    return diff % periodOfDays == 0
}


/**
 * @return true if date is within the daysOfWeek, false if otherwise
 * */
private fun List<DayOfWeek>.checkDayOfWeek(currentDate: LocalDate): Boolean {
    return this.contains(currentDate.dayOfWeek.FHIRDayOfWeek)
}

