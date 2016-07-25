package spinal.tester.scalatest

import java.nio.charset.Charset

import org.scalatest.{BeforeAndAfterAll, ParallelTestExecution, FunSuite}
import spinal.core._

import scala.concurrent.Await
import scala.sys.process._

abstract class SpinalTesterCocotbBase extends FunSuite  {

  var withWaveform = false
  var spinalMustPass = true
  var cocotbMustPass = true

  def doTest: Unit ={
      try {
        backendConfig(SpinalConfig(mode = Verilog,dumpWave = DumpWaveConfig(depth = 1))).generate(createToplevel)
      } catch {
        case e: Throwable => {
          if(spinalMustPass)
            throw e
          return
        }
      }
      assert(spinalMustPass,"Spinal has not fail :(")

      doCmd(Seq(
        s"cd $pythonTestLocation",
        "make")
      )
      val pass = getCocotbPass()
      assert(!cocotbMustPass || pass,"Simulation fail")
      assert(cocotbMustPass || !pass,"Simulation has not fail :(")
      postTest
  }

  test(getName + "Verilog") {doTest}




  def doCmd(cmds : Seq[String]): Unit ={
    var out,err : String = null
        val io = new ProcessIO(
          stdin  => {
            for(cmd <- cmds)
              stdin.write((cmd + "\n").getBytes)
            stdin.close()
          },
          stdout => {
            out = scala.io.Source.fromInputStream(stdout).getLines.foldLeft("")(_ + "\n" + _)
            stdout.close()
          },
          stderr => {
            err = scala.io.Source.fromInputStream(stderr).getLines.foldLeft("")(_ + "\n" + _)
            stderr.close()
          })
        val proc = Process("sh").run(io)
    proc.exitValue()
    println(out)
    println(err)
  }

  def getCocotbPass() : Boolean = {
    import scala.io.Source
    for(line <- Source.fromFile(pythonTestLocation + "/results.xml").getLines()) {
      if (line.contains("failure")){
        return false
      }
    }
    return true
  }

  def postTest : Unit = {}

  def backendConfig(config: SpinalConfig) : SpinalConfig = config
  def getName: String = this.getClass.getName()
  def createToplevel: Component
  def pythonTestLocation : String
}
