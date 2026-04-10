package agents4s.prompt

import scala.io.Source

object PromptTemplate:

  /** Replace every `{{key}}` in `template` with the corresponding value. */
  def substitute(template: String, values: Map[String, String]): String =
    values.foldLeft(template) { case (s, (k, v)) => s.replace(s"{{$k}}", v) }

  /** Load `prompts/<name>` from classpath resources. */
  def loadResource(name: String): String =
    val path = s"prompts/$name"
    val stream = Option(
      Thread.currentThread().getContextClassLoader.getResourceAsStream(path)
    ).orElse(Option(getClass.getClassLoader.getResourceAsStream(path)))
      .getOrElse:
        throw IllegalArgumentException(s"Missing classpath resource: $path")
    try Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  /** Convenience: load a classpath prompt and substitute values in one call. */
  def load(name: String, values: Map[String, String] = Map.empty): String =
    substitute(loadResource(name), values)

end PromptTemplate
