package io.github.paulcharp.diveslate

import io.github.paulcharp.diveslate.core.AIR
import io.github.paulcharp.diveslate.core.Cylinder
import io.github.paulcharp.diveslate.core.Dive
import io.github.paulcharp.diveslate.core.GasMix
import io.github.paulcharp.diveslate.core.GasSwitch
import io.github.paulcharp.diveslate.core.Sample
import java.time.LocalDateTime

/**
 * A dive to draw, built rather than read.
 *
 * The conformance fixtures are core's business and are held to as a
 * specification there. What these tests need is something with a profile on it,
 * so a synthetic dive keeps the app's tests independent of a directory two
 * levels above the module — and keeps a fixture change from failing a test that
 * has nothing to say about fixtures.
 */
object TestDives {

    /**
     * Forty minutes to 42 m with a deco obligation and a switch to nitrox.
     *
     * Carries something for every figure the picker offers, so a test asking
     * "does this render" cannot pass by drawing an empty slate.
     */
    fun reference(): Dive {
        val samples = buildList {
            // Descent, bottom with a little sawtooth, then a stop at 6 m.
            for (i in 0..8) add(Sample(timeSeconds = i * 20.0, depthMetres = i * 5.25))
            for (i in 0..40) {
                val depth = 42.0 - (i % 4) * 1.5
                add(
                    Sample(
                        timeSeconds = 160.0 + i * 30.0,
                        depthMetres = depth,
                        tempCelsius = 18.0 - i * 0.02,
                        inDeco = i > 20,
                        stopDepthMetres = if (i > 20) 6.0 else null,
                    )
                )
            }
            for (i in 0..20) {
                add(
                    Sample(
                        timeSeconds = 1400.0 + i * 30.0,
                        depthMetres = 6.0,
                        tempCelsius = 17.0,
                        inDeco = i < 18,
                        stopDepthMetres = if (i < 18) 6.0 else null,
                    )
                )
            }
            add(Sample(timeSeconds = 2100.0, depthMetres = 0.0, tempCelsius = 19.0))
        }

        return Dive(
            samples = samples,
            cylinders = listOf(
                Cylinder(
                    gas = AIR,
                    description = "AL80",
                    sizeLitres = 11.1,
                    startBar = 207.0,
                    endBar = 60.0,
                ),
            ),
            gasSwitches = listOf(GasSwitch(timeSeconds = 1400.0, gas = GasMix(o2 = 0.5))),
            number = 118,
            whenLogged = LocalDateTime.of(2025, 8, 18, 10, 15),
            site = "Shark and Yolanda Reef",
            waterTempCelsius = 17.0,
            sacLitresPerMin = 15.4,
            cns = 0.32,
            decoModel = "Bühlmann ZHL-16C + GF 40/85",
        )
    }
}
