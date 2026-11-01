package app.conectoma.v3

import android.app.Application
import app.conectoma.v3.engine.AutonomousLearner
import app.conectoma.v3.engine.AutonomousScheduler
import app.conectoma.v3.engine.ConectomaRuntime
import app.conectoma.v3.engine.ConnectomeGraph
import app.conectoma.v3.engine.DictionaryEngine
import app.conectoma.v3.engine.DopamineSystem
import app.conectoma.v3.engine.SynapticMotor
import app.conectoma.v3.engine.UrlLearner

class ConectomaApp : Application(), ConectomaRuntime {
    override val graph = ConnectomeGraph()
    val dictionary = DictionaryEngine()
    val dopamine = DopamineSystem()
    val motor by lazy { SynapticMotor(graph, dictionary, dopamine) }
    val urls by lazy { UrlLearner(dictionary) }
    override val autonomous by lazy { AutonomousLearner(motor, dictionary) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AutonomousScheduler.start(this)
    }

    companion object {
        lateinit var instance: ConectomaApp
            private set
    }
}
