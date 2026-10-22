package com.ai.tinymodels.core.common

import android.content.Context
import io.mockk.every
import io.mockk.mockk

/**
 * A mocked [Context] whose `getString` returns a deterministic marker instead of
 * real resource text (unit tests have no Android resources):
 *
 *  - `getString(id)`        -> `"<id>"`
 *  - `getString(id, args…)` -> `"<id>(arg1,arg2)"`
 *
 * That lets ViewModel tests assert *which* string resource was requested
 * (`R.string.foo`) instead of duplicating the English copy.
 */
fun fakeStringContext(): Context = mockk<Context>(relaxed = true).also { ctx ->
    every { ctx.getString(any()) } answers { firstArg<Int>().toString() }
    every { ctx.getString(any(), *anyVararg()) } answers {
        firstArg<Int>().toString() +
            "(" + invocation.args.drop(1)
                .flatMap { arg -> if (arg is Array<*>) arg.toList() else listOf(arg) }
                .joinToString(",") + ")"
    }
}
