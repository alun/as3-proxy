import apparat.swf._
import apparat.abc._
import apparat.bytecode._
import apparat.utils._
import apparat.bytecode.combinator._
import BytecodeChains._
import apparat.bytecode.operations._
import apparat.abc.analysis._
import java.io.FileOutputStream

object SwfTool {

  import apparat.abc.AbcNamespaceKind._

  // specify which static method we can use for proxy
  val proxyPackage = AbcNamespace(Package,Symbol(""))
  val proxyClass = AbcQName('Hook, proxyPackage)
  val rootPackage = AbcNamespace(Package,Symbol(""))
  val proxyMethod = AbcQName('setupEventListener, rootPackage)

  class Formatter(s:String) {
    def format(replacements:Seq[(String,Any)]):String = replacements match {
      case h :: xs => s.replace("${" + h._1 + "}", h._2.toString()).format(xs)
      case _ => s
    }
  }

  def printUsage() {
    val usage = """
      |  Usage: ${className} file.swf {proxify, mainClass, dump, soundCheck}
      |
      |  Where:
      |    file.swf - path to file you want work with
      |
      |    proxify - make an addEventListener proxied version of the swf
      |    mainClass - print a mainclass name
      |    soundCheck - find SoundMixer usage
      |    dump - prints bytecode dump on the screen""" .stripMargin
    println(new Formatter(usage).format {
        "className" -> SwfTool.getClass.getName :: Nil
    })
  }

  def main(args: Array[String]) {
    (for {
      file <- args.headOption
    } yield {
      args.drop(1).headOption match {
        case Some("proxify") =>
          // we get a "proxy" instuction so lets change addEventListener calls to proxy
          proxify(file)
        case Some("mainClass") =>
          mainClass(file)
        case Some("soundCheck") =>
          findSoundIssues(file)
        case Some("dump") =>
          dump(file)
        case _ =>
          printUsage
      }
    }) match {
      case None => printUsage
      case _ =>
    }
  }

  def mainClass(file: String) {
    val swf = Swf fromFile file
    println(swf.mainClass)
  }

  def dump(file: String) {
    val swf = Swf fromFile file
    for {
      tag <- swf
      abc <- Abc fromTag tag
    } {
      abc.loadBytecode()
      for {
        method <- abc.methods
      } {
        method.dump()
      }
    }
  }

  def binary(file:String) {
    val swf = Swf fromFile file
    swf.tags.foldLeft(0) { (idx, tag) => tag match {
      case tag: DefineBinaryData =>
        new FileOutputStream("output%d.swf" format idx).write(tag.data)
        idx + 1
      case _ => idx
    } }
  }

  def proxify(file: String) {
      SwfTags.tagFactory = (kind: Int) => kind match {
        case SwfTags.DoABC => Some(new DoABC)
        case SwfTags.DoABC1 => Some(new DoABC)
        case _ => None
      }

      val cont = TagContainer fromFile file
      cont foreachTagSync proxify
      cont write file
  }


  private def proxify: PartialFunction[SwfTag, Unit] = {
    case doABC: DoABC =>
      val abc = Abc fromDoABC doABC

      abc.loadBytecode()
      var replaced = false

      for {
        method <- abc.methods
        body <- method.body
        bytecode <- body.bytecode
      } {
        var flag = true
        while (flag) {
          flag = replaceAddEventListener(bytecode)
          if (flag) {
            replaced = true
          }
        }
        if (replaced) {
          body.maxStack += 1
        }
      }

      if (replaced) {
        abc.cpool = AbcConstantPoolBuilder using abc
        abc.saveBytecode()
        abc write doABC
      }
  }

  def findSoundIssues(file: String) {
    SwfTags.tagFactory = (kind: Int) => kind match {
      case SwfTags.DoABC => Some(new DoABC)
      case SwfTags.DoABC1 => Some(new DoABC)
      case _ => None
    }

    val container = TagContainer fromFile file
    val violations = container.tags.collect(findSoundMixerUsage).reduceLeftOption(_ ++ _).getOrElse(Nil)

    if (!violations.isEmpty) {
      println("Global SoundMixer access detected!")
      violations.foreach {
        case (method, ops) =>
          println("Method: " + method.name)
          ops.foreach(println)
      }
      System.exit(1)
    }
    else {
      println("No global sound access detected")
      System.exit(0)
    }
  }

  def firstMixerResetIn(ops:List[AbstractOp]): List[AbstractOp] = {
    val soundMixerUsageStart = ops.dropWhile {
      case
        FindPropStrict(AbcQName('SoundMixer, AbcNamespace(AbcNamespaceKind.Package, Symbol("flash.media")))) |
        GetLex(AbcQName('SoundMixer, AbcNamespace(AbcNamespaceKind.Package, Symbol("flash.media")))) =>
        false
      case _ => true
    }

    def streamIt[T, B](coll: List[T], v: B)(f: (T, B) => B): Stream[(T, B)] =
      coll match {
        case e :: _ =>
          val nv = f(e, v)
          (e, nv) #:: streamIt(coll.tail, nv)(f)
        case _ => Stream.empty
      }

    val opsWithStackSizes = streamIt(soundMixerUsageStart, 0) {
      case (op, stackSize) =>
        stackSize + op.pushOperands - op.popOperands
    }

    val soundMixerOps = {
      val (ops, nextOps) = opsWithStackSizes.span(_._2 != 0)
      ops #::: nextOps.take(1)
    }.map(_._1)

    val soundMixerTouched = soundMixerOps.exists {
      case SetProperty(AbcQName('soundTransform, AbcNamespace(AbcNamespaceKind.Package, Symbol("")))) => true
      case _ => false
    }

    if (soundMixerTouched) {
      soundMixerOps.toList 
    } else {
      val restOps = soundMixerUsageStart.drop(soundMixerOps.size)
      if (restOps.nonEmpty) {
        firstMixerResetIn(restOps)
      }
      else {
        Nil
      }
    }
  }

  def findGlobalMixerReset(method:AbcMethod):List[AbstractOp] = {
    val result = for {
      body <- method.body
      bytecode <- body.bytecode
      ops = bytecode.ops
    } yield {
      firstMixerResetIn(ops)
    }
    result.getOrElse(Nil)
  }


  private def findSoundMixerUsage: PartialFunction[SwfTag, List[(AbcMethod, List[AbstractOp])]] = {
    case doABC: DoABC =>
      val abc = Abc fromDoABC doABC
      abc.loadBytecode()
      for {
        method <- abc.methods.toList
        ops = findGlobalMixerReset(method)
        if !ops.isEmpty
      } yield (method, ops)

  }

  /**
   * This method can be used to block stage access from 3rd part libraries (such as GoogleMap)
   * by implementing a handler which will maintain a real addEventListener logic
   */
  private def replaceAddEventListener(bytecode: Bytecode) = {
    val ops = bytecode.ops

    // try to see if bytecode has addEventListener call operation
    ops.find {
      case CallProperty(AbcQName('addEventListener, _), _) => true
      case CallPropVoid(AbcQName('addEventListener, _), _) => true
      case _ => false
    } .exists { addEventListenerOp =>
      // if so let replace it to call a proxy instead
      val idx = ops.indexOf(addEventListenerOp)
      val (beforeOps, _) = ops.splitAt(idx)
      val (_, paramOps) = beforeOps.reverse.foldLeft((addEventListenerOp.popOperands, List.empty[AbstractOp])) {
        // harvest previous Operations which was used to fill the stack with call params
        case ((stackSize, ops), op) if stackSize > 0 =>
          (stackSize - op.pushOperands + op.popOperands, op :: ops)
        // all operations are harvested, so skip to the end of the list
        case ((stackSize, ops), _) => (stackSize, ops)
      }
      // now we need to build a chain which we want to replace
      val chain = (paramOps :+ addEventListenerOp).foldLeft[BytecodeChain[_]](null) {
        case (chain, firstOp) if chain == null => filter { case `firstOp` => true }
        case (chain, anotherOp) => chain ~ filter { case `anotherOp` => true }
      }
      val newChain = FindPropStrict(proxyClass) ::
        GetProperty(proxyClass) ::
        paramOps :::
        CallProperty(proxyMethod, addEventListenerOp.popOperands) ::
        (if (addEventListenerOp.opCode == Op.callpropvoid) Pop() :: Nil else Nil)

      // replace the chain with proxy call chain
      val result = bytecode.replace(chain)(_ => newChain)
      newChain.filter(bytecode.markers.hasMarkerFor _) match {
        case op :: rest => bytecode.markers.forwardMarker(op, newChain(0))
        case _ =>
      }
      result
    }
  }

}
