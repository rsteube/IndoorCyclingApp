/*
 * Copyright (C) 2026 Snapp Technology Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.indoorbike.shared.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one question an import asks: may it replace a workout the rider already
 * has?
 *
 * An import used to write by id without asking. A workout link is one tap in
 * a chat or on a web page, and a workout's id is whatever the link says, so a
 * link could replace any workout in the library - and sync then carried the
 * replacement to every other device. Now the importer asks here, and waits;
 * the app shows [pending] as a dialog wherever the rider is, and answers.
 *
 * App-scoped, so the question outlives the screen or activity that raised it:
 * a rotation re-shows it rather than dropping the import.
 */
class ImportQuestions {

    enum class Answer {
        /** Overwrite the workouts already there with the incoming ones. */
        REPLACE,

        /** Add the incoming workouts beside the ones already there. */
        KEEP_BOTH,

        /** Import nothing. */
        CANCEL,
    }

    /** A question waiting for the rider. [names] are the workouts that would be replaced. */
    class Question internal constructor(val names: List<String>) {
        internal val answer = CompletableDeferred<Answer>()
    }

    private val _pending = MutableStateFlow<Question?>(null)

    /** The question to show, or null when there is none. */
    val pending: StateFlow<Question?> = _pending.asStateFlow()

    /** One question at a time: a second import waits its turn. */
    private val asking = Mutex()

    /** Ask about replacing [names], and wait for the answer. */
    suspend fun ask(names: List<String>): Answer = asking.withLock {
        val question = Question(names)
        _pending.value = question
        try {
            question.answer.await()
        } finally {
            _pending.compareAndSet(question, null)
        }
    }

    /** The rider's answer to [question]. A second answer, or one to a question gone stale, is ignored. */
    fun answer(question: Question, answer: Answer) {
        question.answer.complete(answer)
    }
}
