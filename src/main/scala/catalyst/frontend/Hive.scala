package catalyst
package frontend
package hive

import scala.collection.JavaConversions._

import org.apache.hadoop.hive.ql.lib.Node
import org.apache.hadoop.hive.ql.parse._

import analysis._
import expressions._
import plans._
import plans.logical._
import types._

/**
 * A logical node that represents a non-query command to be executed by the system.  For example,
 * commands can be used by parsers to represent DDL operations.
 */
abstract class Command extends LeafNode {
  self: Product =>
  def output = Seq.empty
}

/**
 * Used when we need to start parsing the AST before deciding that we are going to pass the command
 * back for Hive to execute natively.  Will be replaced with a native command that contains the
 * cmd string.
 */
case object NativePlaceholder extends Command

/**
 * Returned for commands supported by the parser, but not catalyst.  In general these are DDL
 * commands that are passed directly to Hive.
 */
case class NativeCommand(cmd: String) extends Command

case class ExplainCommand(plan: LogicalPlan) extends Command

case class DfsCommand(cmd: String) extends Command

case class ShellCommand(cmd: String) extends Command

case class SourceCommand(filePath: String) extends Command

case class ConfigurationAssignment(cmd: String) extends Command

case class AddJar(jarPath: String) extends Command

case class AddFile(filePath: String) extends Command

object HiveQl {
  protected val nativeCommands = Seq(
    "TOK_DESCFUNCTION",
    "TOK_DESCTABLE",
    "TOK_DESCDATABASE",
    "TOK_SHOW_TABLESTATUS",
    "TOK_SHOWDATABASES",
    "TOK_SHOWFUNCTIONS",
    "TOK_SHOWINDEXES",
    "TOK_SHOWINDEXES",
    "TOK_SHOWPARTITIONS",
    "TOK_SHOWTABLES",

    "TOK_LOCKTABLE",
    "TOK_SHOWLOCKS",
    "TOK_UNLOCKTABLE",

    "TOK_CREATEROLE",
    "TOK_DROPROLE",
    "TOK_GRANT",
    "TOK_GRANT_ROLE",
    "TOK_REVOKE",
    "TOK_SHOW_GRANT",
    "TOK_SHOW_ROLE_GRANT",

    "TOK_CREATEFUNCTION",
    "TOK_DROPFUNCTION",

    "TOK_ALTERDATABASE_PROPERTIES",
    "TOK_ALTERINDEX_PROPERTIES",
    "TOK_ALTERINDEX_REBUILD",
    "TOK_ALTERTABLE_ADDCOLS",
    "TOK_ALTERTABLE_ADDPARTS",
    "TOK_ALTERTABLE_ARCHIVE",
    "TOK_ALTERTABLE_CLUSTER_SORT",
    "TOK_ALTERTABLE_DROPPARTS",
    "TOK_ALTERTABLE_PARTITION",
    "TOK_ALTERTABLE_PROPERTIES",
    "TOK_ALTERTABLE_RENAME",
    "TOK_ALTERTABLE_RENAMECOL",
    "TOK_ALTERTABLE_REPLACECOLS",
    "TOK_ALTERTABLE_TOUCH",
    "TOK_ALTERTABLE_UNARCHIVE",
    "TOK_ANALYZE",
    "TOK_CREATEDATABASE",
    "TOK_CREATEFUNCTION",
    "TOK_CREATEINDEX",
    "TOK_DROPDATABASE",
    "TOK_DROPINDEX",
    "TOK_DROPTABLE",
    "TOK_MSCK",

    // TODO(marmbrus): Figure out how view are expanded by hive, as we might need to handle this.
    "TOK_ALTERVIEW_ADDPARTS",
    "TOK_ALTERVIEW_DROPPARTS",
    "TOK_ALTERVIEW_PROPERTIES",
    "TOK_ALTERVIEW_RENAME",
    "TOK_CREATEVIEW",
    "TOK_DROPVIEW",

    "TOK_EXPORT",
    "TOK_IMPORT",
    "TOK_LOAD",

    "TOK_SWITCHDATABASE"
  )

  /**
   * A set of implicit transformations that allow Hive ASTNodes to be rewritten by transformations similar to
   * [[catalyst.trees.TreeNode]].
   *
   * Note that this should be considered very experimental and is not indented as a replacement for TreeNode.  Primarily
   * it should be noted ASTNodes are not immutable and do not appear to have clean copy semantics.  Therefore, users of
   * this class should take care when copying/modifying trees that might be used elsewhere.
   */
  implicit class TransformableNode(n: ASTNode) {
    /**
     * Returns a copy of this node where [[rule]] has been recursively
     * applied to it and all of its children.  When [[rule]] does not
     * apply to a given node it is left unchanged.
     * @param rule the function use to transform this nodes children
     */
    def transform(rule: PartialFunction[ASTNode, ASTNode]): ASTNode = {
      try {
        val afterRule = rule.applyOrElse(n, identity[ASTNode])
        afterRule.withChildren(
          nilIfEmpty(afterRule.getChildren)
            .asInstanceOf[Seq[ASTNode]]
            .map(ast => Option(ast).map(_.transform(rule)).orNull))
      } catch {
        case e: Exception =>
          println(dumpTree(n))
          throw e
      }
    }

    /**
     * Returns a scala.Seq equivilent to [s] or Nil if [s] is null.
     */
    private def nilIfEmpty[A](s: java.util.List[A]): Seq[A] =
      Option(s).map(_.toSeq).getOrElse(Nil)

    /**
     * Returns this ASTNode with the text changed to [[newText]].
     */
    def withText(newText: String): ASTNode = {
      n.token.asInstanceOf[org.antlr.runtime.CommonToken].setText(newText)
      n
    }

    /**
     * Returns this ASTNode with the children changed to [[newChildren]].
     */
    def withChildren(newChildren: Seq[ASTNode]): ASTNode = {
      (1 to n.getChildCount).foreach(_ => n.deleteChild(0))
      n.addChildren(newChildren)
      n
    }

    /**
     * Throws an error if this is not equal to other.
     *
     * Right now this function only checks the name, type, text and children of the node
     * for equality.
     */
    def checkEquals(other: ASTNode) {
      def check(field: String, f: ASTNode => Any) =
        if (f(n) != f(other))
          sys.error(s"$field does not match for trees. '${f(n)}' != '${f(other)}' left: ${dumpTree(n)}, right: ${dumpTree(other)}")

      check("name", _.getName)
      check("type", _.getType)
      check("text", _.getText)
      check("numChildren", n => nilIfEmpty(n.getChildren).size)

      val leftChildren = nilIfEmpty(n.getChildren).asInstanceOf[Seq[ASTNode]]
      val rightChildren = nilIfEmpty(other.getChildren).asInstanceOf[Seq[ASTNode]]
      leftChildren zip rightChildren foreach {
        case (l,r) => l checkEquals r
      }
    }
  }

  class ParseException(sql: String, cause: Throwable)
    extends Exception(s"Failed to parse: $sql", cause)

  /**
   * Returns the AST for the given SQL string.
   */
  def getAst(sql: String): ASTNode = {
    ParseUtils.findRootNonNullToken(
      (new ParseDriver()).parse(sql))
  }

  def parseSql(sql: String): LogicalPlan = {
    try {
      if (sql.toLowerCase.startsWith("set"))
        ConfigurationAssignment(sql)
      else if (sql.toLowerCase.startsWith("add jar"))
        AddJar(sql.drop(8))
      else if (sql.toLowerCase.startsWith("add file"))
        AddFile(sql.drop(9))
      else if (sql.startsWith("dfs"))
        DfsCommand(sql)
      else if (sql.startsWith("source"))
        SourceCommand(sql.split(" ").toSeq match { case Seq("source", filePath) => filePath })
      else if (sql.startsWith("!"))
        ShellCommand(sql.drop(1))
      else {
        val tree = getAst(sql)

        if (nativeCommands contains tree.getText)
          NativeCommand(sql)
        else
          nodeToPlan(tree) match {
            case NativePlaceholder => NativeCommand(sql)
            case other => other
          }
      }
    } catch {
      case e: Exception => throw new ParseException(sql, e)
    }
  }

  def parseDdl(ddl: String): Seq[Attribute] = {
    val tree =
      try {
        ParseUtils.findRootNonNullToken(
          (new ParseDriver()).parse(ddl, null /* no context required for parsing alone */))
      } catch {
        case pe: org.apache.hadoop.hive.ql.parse.ParseException =>
          throw new RuntimeException(s"Failed to parse ddl: '$ddl'", pe)
      }
    assert(tree.asInstanceOf[ASTNode].getText == "TOK_CREATETABLE", "Only CREATE TABLE supported.")
    val tableOps = tree.getChildren
    val colList =
      tableOps
        .find(_.asInstanceOf[ASTNode].getText == "TOK_TABCOLLIST")
        .getOrElse(sys.error("No columnList!")).getChildren

    colList.map(nodeToAttribute)
  }

  /** Extractor for matching Hive's AST Tokens. */
  object Token {
    /** @return matches of the form (tokenName, children). */
    def unapply(t: Any) = t match {
      case t: ASTNode =>
        Some((t.getText, Option(t.getChildren).map(_.toList).getOrElse(Nil).asInstanceOf[Seq[ASTNode]]))
      case _ => None
    }
  }

  protected def getClauses(clauseNames: Seq[String], nodeList: Seq[ASTNode]): Seq[Option[Node]] = {
    var remainingNodes = nodeList
    val clauses = clauseNames.map { clauseName =>
      val (matches, nonMatches) = remainingNodes.partition(_.getText.toUpperCase == clauseName)
      remainingNodes = nonMatches ++ (if (matches.nonEmpty) matches.tail else Nil)
      matches.headOption
    }

    assert(remainingNodes.isEmpty,
      s"Unhandled clauses: ${remainingNodes.map(dumpTree(_)).mkString("\n")}")
    clauses
  }

  def getClause(clauseName: String, nodeList: Seq[Node]) =
    getClauseOption(clauseName, nodeList)
      .getOrElse(sys.error(s"Expected clause $clauseName missing from ${nodeList.map(dumpTree(_)).mkString("\n")}"))

  def getClauseOption(clauseName: String, nodeList: Seq[Node]): Option[Node] = {
    nodeList.filter { case ast: ASTNode => ast.getText == clauseName } match {
      case Seq(oneMatch) => Some(oneMatch)
      case Seq() => None
      case _ => sys.error(s"Found multiple instances of clause $clauseName")
    }
  }

  protected def nodeToAttribute(node: Node): Attribute = node match {
    case Token("TOK_TABCOL",
           Token(colName, Nil) ::
           dataType :: Nil) =>
      AttributeReference(colName, nodeToDataType(dataType), true)()

    case a: ASTNode =>
      throw new NotImplementedError(s"No parse rules for:\n ${dumpTree(a).toString} ")
  }

  protected def nodeToDataType(node: Node): DataType = node match {
    case Token("TOK_BIGINT", Nil) => IntegerType
    case Token("TOK_INT", Nil) => IntegerType
    case Token("TOK_TINYINT", Nil) => IntegerType
    case Token("TOK_SMALLINT", Nil) => IntegerType
    case Token("TOK_BOOLEAN", Nil) => BooleanType
    case Token("TOK_STRING", Nil) => StringType
    case Token("TOK_FLOAT", Nil) => FloatType
    case Token("TOK_DOUBLE", Nil) => FloatType
    case Token("TOK_LIST", elementType :: Nil) => ArrayType(nodeToDataType(elementType))
    case Token("TOK_STRUCT",
           Token("TOK_TABCOLLIST", fields) :: Nil) =>
      StructType(fields.map(nodeToStructField))
    case Token("TOK_MAP",
           keyType ::
           valueType :: Nil) =>
      MapType(nodeToDataType(keyType), nodeToDataType(valueType))
    case a: ASTNode =>
      throw new NotImplementedError(s"No parse rules for DataType:\n ${dumpTree(a).toString} ")
  }

  protected def nodeToStructField(node: Node): StructField = node match {
    case Token("TOK_TABCOL",
           Token(fieldName, Nil) ::
           dataType :: Nil) =>
      StructField(fieldName, nodeToDataType(dataType))
    case Token("TOK_TABCOL",
           Token(fieldName, Nil) ::
             dataType ::
             _ /* comment */:: Nil) =>
      StructField(fieldName, nodeToDataType(dataType) )
    case a: ASTNode =>
      throw new NotImplementedError(s"No parse rules for StructField:\n ${dumpTree(a).toString} ")
  }

  protected def nameExpressions(exprs: Seq[Expression]): Seq[NamedExpression] = {
    exprs.zipWithIndex.map {
      case (ne: NamedExpression, _) => ne
      case (e, i) => Alias(e, s"c_$i")()
    }
  }

  protected def nodeToPlan(node: Node): LogicalPlan = node match {
    // Just fake explain on create function...
    case Token("TOK_EXPLAIN", Token("TOK_CREATEFUNCTION", _) :: Nil) => NoRelation
    case Token("TOK_EXPLAIN", explainArgs) =>
      // Ignore FORMATTED if present.
      val Some(query) :: _ :: _ :: Nil = getClauses(Seq("TOK_QUERY", "FORMATTED", "EXTENDED"), explainArgs)
      // TODO: support EXTENDED?
      ExplainCommand(nodeToPlan(query))

    case Token("TOK_CREATETABLE", children) if children.collect { case t@Token("TOK_QUERY", _) => t }.nonEmpty =>
      val (Some(Token("TOK_TABNAME", Token(tableName, Nil) :: Nil)) ::
          _ /* likeTable */ ::
          Some(query) :: Nil) = getClauses(Seq("TOK_TABNAME", "TOK_LIKETABLE", "TOK_QUERY"), children)
      InsertIntoCreatedTable(tableName, nodeToPlan(query))

    // If its not a "CREATE TABLE AS" like above then just pass it back to hive as a native command.
    case Token("TOK_CREATETABLE", _) => NativePlaceholder

    case Token("TOK_QUERY",
           Token("TOK_FROM", fromClause :: Nil) ::
           insertClauses) =>

      // Return one query for each insert clause.
      val queries = insertClauses.map { case Token("TOK_INSERT", singleInsert) =>
        val (Some(destClause) ::
            Some(selectClause) ::
            whereClause ::
            groupByClause ::
            orderByClause ::
            sortByClause ::
            limitClause :: Nil) = getClauses(Seq("TOK_DESTINATION", "TOK_SELECT", "TOK_WHERE", "TOK_GROUPBY", "TOK_ORDERBY", "TOK_SORTBY", "TOK_LIMIT"), singleInsert)

        val relations = nodeToRelation(fromClause)
        val withWhere = whereClause.map { whereNode =>
          val Seq(whereExpr) = whereNode.getChildren().toSeq
          Filter(nodeToExpr(whereExpr), relations)
        }.getOrElse(relations)


        // Script transformations are expressed as a select clause with a single expression of type
        // TOK_TRANSFORM
        val transformation = selectClause.getChildren.head match {
          case Token("TOK_SELEXPR",
                 Token("TOK_TRANSFORM",
                   Token("TOK_EXPLIST", inputExprs) ::
                   Token("TOK_SERDE", Nil) ::
                   Token("TOK_RECORDWRITER", Nil) :: // TODO: Need to support other types of (in/out)put
                   Token(script, Nil)::
                   Token("TOK_SERDE", Nil) ::
                   Token("TOK_RECORDREADER", Nil) ::
                   Token("TOK_ALIASLIST", aliases) :: Nil) :: Nil) =>

            val output = aliases.map { case Token(n, Nil) => AttributeReference(n, StringType)() }
            val unescapedScript = BaseSemanticAnalyzer.unescapeSQLString(script)
            Some(Transform(inputExprs.map(nodeToExpr), unescapedScript, output, withWhere))
          case _ => None
        }

        // The projection of the query can either be a normal projection, an aggregation (if there is a group by) or
        // a script transformation.
        val withProject = transformation.getOrElse {
          // Not a transformation so must be either project or aggregation.
          val selectExpressions = nameExpressions(selectClause.getChildren.flatMap(selExprNodeToExpr))

          groupByClause match {
            case Some(groupBy) => Aggregate(groupBy.getChildren.map(nodeToExpr), selectExpressions, withWhere)
            case None => Project(selectExpressions, withWhere)
          }
        }

        require(!(orderByClause.isDefined && sortByClause.isDefined), "Can't have both a sort by and order by.")
        // Right now we treat sorting and ordering as identical.
        val withSort =
          (orderByClause orElse sortByClause)
            .map(_.getChildren.map(nodeToSortOrder))
            .map(Sort(_, withProject))
            .getOrElse(withProject)
        val withLimit =
          limitClause.map(l => nodeToExpr(l.getChildren.head))
            .map(StopAfter(_, withSort))
            .getOrElse(withSort)

        nodeToDest(
          destClause,
          withLimit)
      }

      // If there are multiple INSERTS just UNION them together into on query.
      queries.reduceLeft(Union)

    case Token("TOK_UNION", left :: right :: Nil) => Union(nodeToPlan(left), nodeToPlan(right))

    case a: ASTNode =>
      throw new NotImplementedError(s"No parse rules for:\n ${dumpTree(a).toString} ")
  }

  val allJoinTokens = "(TOK_.*JOIN)".r
  def nodeToRelation(node: Node): LogicalPlan = node match {
    case Token("TOK_SUBQUERY",
           query :: Token(alias, Nil) :: Nil) =>
      Subquery(alias, nodeToPlan(query))

    /* Table, No Alias */
    case Token("TOK_TABREF",
           Token("TOK_TABNAME",
             tableNameParts) :: Nil) =>
      val tableName = tableNameParts.map { case Token(part, Nil) => part }.mkString(".")
      UnresolvedRelation(tableName, None)

    case Token("TOK_UNIQUEJOIN", joinArgs) =>
      val tableOrdinals =
        joinArgs.zipWithIndex.filter {
          case (arg, i) => arg.getText == "TOK_TABREF"
        }.map(_._2)

      val isPreserved = tableOrdinals.map(i => (i - 1 < 0) || joinArgs(i - 1).getText == "PRESERVE")
      val tables = tableOrdinals.map(i => nodeToRelation(joinArgs(i)))
      val joinExpressions = tableOrdinals.map(i => joinArgs(i + 1).getChildren.map(nodeToExpr))

      val joinConditions = joinExpressions.sliding(2).map {
        case Seq(c1, c2) =>
          val predicates = (c1, c2).zipped.map { case (e1, e2) => Equals(e1, e2): Expression }
          predicates.reduceLeft(And)
      }.toBuffer

      val joinType = isPreserved.sliding(2).map {
        case Seq(true, true) => FullOuter
        case Seq(true, false) => LeftOuter
        case Seq(false, true) => RightOuter
        case Seq(false, false) => Inner
      }.toBuffer

      val joinedTables = tables.reduceLeft(Join(_,_, Inner, None))

      // Must be transform down.
      val joinedResult = joinedTables transform {
        case j: Join =>
          j.copy(
            condition = Some(joinConditions.remove(joinConditions.length - 1)),
            joinType = joinType.remove(joinType.length - 1))
      }

      val groups = (0 until joinExpressions.head.size).map(i => Coalesce(joinExpressions.map(_(i))))

      // Unique join is not really the same as an outer join so we must group together results where
      // the joinExpressions are the same, taking the First of each value is only okay because the
      // user of a unique join is implicitly promising that there is only one result.
      // TODO: This doesn't actually work since [[Star]] is not a valid aggregate expression.
      // instead we should figure out how important supporting this feature is and whether it is
      // worth the number of hacks that will be required to implement it.  Namely, we need to add
      // some sort of mapped star expansion that would expand all child output row to be similarly
      // named output expressions where some aggregate expression has been applied (i.e. First).
      ??? /// Aggregate(groups, Star(None, First(_)) :: Nil, joinedResult)

    /* Table with Alias */
    case Token("TOK_TABREF",
           Token("TOK_TABNAME",
             tableNameParts) ::
             Token(alias, Nil) :: Nil) =>
      val tableName = tableNameParts.map { case Token(part, Nil) => part }.mkString(".")
      UnresolvedRelation(tableName, Some(alias))

    case Token(allJoinTokens(joinToken),
           relation1 ::
           relation2 :: other) =>
      assert(other.size <= 1, s"Unhandled join child ${other}")
      val joinType = joinToken match {
        case "TOK_JOIN" => Inner
        case "TOK_RIGHTOUTERJOIN" => RightOuter
        case "TOK_LEFTOUTERJOIN" => LeftOuter
        case "TOK_FULLOUTERJOIN" => FullOuter
      }
      Join(nodeToRelation(relation1),
        nodeToRelation(relation2),
        joinType,
        other.headOption.map(nodeToExpr))

    case a: ASTNode =>
      throw new NotImplementedError(s"No parse rules for:\n ${dumpTree(a).toString} ")
  }

  def nodeToSortOrder(node: Node): SortOrder = node match {
    case Token("TOK_TABSORTCOLNAMEASC", sortExpr :: Nil) =>
      SortOrder(nodeToExpr(sortExpr), Ascending)
    case Token("TOK_TABSORTCOLNAMEDESC", sortExpr :: Nil) =>
      SortOrder(nodeToExpr(sortExpr), Descending)

    case a: ASTNode =>
      throw new NotImplementedError(s"No parse rules for:\n ${dumpTree(a).toString} ")
  }

  protected def nodeToDest(node: Node, query: LogicalPlan): LogicalPlan = node match {
    case Token("TOK_DESTINATION",
           Token("TOK_DIR",
             Token("TOK_TMP_FILE", Nil) :: Nil) :: Nil) =>
      query

    case Token("TOK_DESTINATION",
           Token("TOK_TAB",
              tableArgs) :: Nil) =>
      val Some(nameClause) :: partitionClause :: Nil =
        getClauses(Seq("TOK_TABNAME", "TOK_PARTSPEC"), tableArgs)
      val Token("TOK_TABNAME", Token(tableName, Nil) :: Nil) = nameClause

      val partitionKeys = partitionClause.map(_.getChildren.map {
        case Token("TOK_PARTVAL", Token(key, Nil) :: Token(value, Nil) :: Nil) => key -> value
      }.toMap).getOrElse(Map.empty)

      InsertIntoTable(UnresolvedRelation(tableName, None), partitionKeys, query)

    case a: ASTNode =>
      throw new NotImplementedError(s"No parse rules for:\n ${dumpTree(a).toString} ")
  }

  protected def selExprNodeToExpr(node: Node): Option[Expression] = node match {
    case Token("TOK_SELEXPR",
           e :: Nil) =>
      Some(nodeToExpr(e))

    case Token("TOK_SELEXPR",
           e :: Token(alias, Nil) :: Nil) =>
      Some(Alias(nodeToExpr(e), alias)())

    /* Hints are ignored */
    case Token("TOK_HINTLIST", _) => None

    case a: ASTNode =>
      throw new NotImplementedError(s"No parse rules for:\n ${dumpTree(a).toString} ")
  }


  protected val escapedIdentifier = "`([^`]+)`".r
  /** Strips backticks from ident if present */
  protected def cleanIdentifier(ident: String): String = ident match {
    case escapedIdentifier(i) => i
    case plainIdent => plainIdent
  }

  val numericAstTypes =
    Seq(HiveParser.Number, HiveParser.TinyintLiteral, HiveParser.SmallintLiteral, HiveParser.BigintLiteral)

  /* Case insensitive matches */
  val COUNT = "(?i)COUNT".r
  val AVG = "(?i)AVG".r
  val SUM = "(?i)SUM".r
  val RAND = "(?i)RAND".r
  val AND = "(?i)AND".r
  val OR = "(?i)OR".r
  val NOT = "(?i)NOT".r
  val TRUE = "(?i)TRUE".r
  val FALSE = "(?i)FALSE".r

  protected def nodeToExpr(node: Node): Expression = node match {
    /* Attribute References */
    case Token("TOK_TABLE_OR_COL",
           Token(name, Nil) :: Nil) =>
      UnresolvedAttribute(cleanIdentifier(name))
    case Token(".", qualifier :: Token(attr, Nil) :: Nil) =>
      nodeToExpr(qualifier) match {
        case UnresolvedAttribute(qualifierName) => UnresolvedAttribute(qualifierName + "." + cleanIdentifier(attr))
      }

    /* Stars (*) */
    case Token("TOK_ALLCOLREF", Nil) => Star(None)
    case Token("TOK_ALLCOLREF", Token("TOK_TABNAME", Token(name, Nil) :: Nil) :: Nil) => Star(Some(name))

    /* Aggregate Functions */
    case Token("TOK_FUNCTION", Token(AVG(), Nil) :: arg :: Nil) => Average(nodeToExpr(arg))
    case Token("TOK_FUNCTION", Token(COUNT(), Nil) :: arg :: Nil) => Count(nodeToExpr(arg))
    case Token("TOK_FUNCTIONSTAR", Token(COUNT(), Nil) :: Nil) => Count(Literal(1))
    case Token("TOK_FUNCTIONDI", Token(COUNT(), Nil) :: args) => CountDistinct(args.map(nodeToExpr))
    case Token("TOK_FUNCTION", Token(SUM(), Nil) :: arg :: Nil) => Sum(nodeToExpr(arg))

    /* Casts */
    case Token("TOK_FUNCTION", Token("TOK_STRING", Nil) :: arg :: Nil) => Cast(nodeToExpr(arg), StringType)
    case Token("TOK_FUNCTION", Token("TOK_INT", Nil) :: arg :: Nil) => Cast(nodeToExpr(arg), IntegerType)
    case Token("TOK_FUNCTION", Token("TOK_FLOAT", Nil) :: arg :: Nil) => Cast(nodeToExpr(arg), FloatType)
    case Token("TOK_FUNCTION", Token("TOK_DOUBLE", Nil) :: arg :: Nil) => Cast(nodeToExpr(arg), DoubleType)
    case Token("TOK_FUNCTION", Token("TOK_SMALLINT", Nil) :: arg :: Nil) => Cast(nodeToExpr(arg), ShortType)
    case Token("TOK_FUNCTION", Token("TOK_TINYINT", Nil) :: arg :: Nil) => Cast(nodeToExpr(arg), ByteType)
    case Token("TOK_FUNCTION", Token("TOK_BINARY", Nil) :: arg :: Nil) => Cast(nodeToExpr(arg), BinaryType)

    /* Arithmetic */
    case Token("-", child :: Nil) => UnaryMinus(nodeToExpr(child))
    case Token("+", left :: right:: Nil) => Add(nodeToExpr(left), nodeToExpr(right))
    case Token("-", left :: right:: Nil) => Subtract(nodeToExpr(left), nodeToExpr(right))
    case Token("*", left :: right:: Nil) => Multiply(nodeToExpr(left), nodeToExpr(right))
    case Token("/", left :: right:: Nil) => Divide(nodeToExpr(left), nodeToExpr(right))
    case Token("DIV", left :: right:: Nil) => Divide(nodeToExpr(left), nodeToExpr(right))

    /* Comparisons */
    case Token("=", left :: right:: Nil) => Equals(nodeToExpr(left), nodeToExpr(right))
    case Token("!=", left :: right:: Nil) => Not(Equals(nodeToExpr(left), nodeToExpr(right)))
    case Token("<>", left :: right:: Nil) => Not(Equals(nodeToExpr(left), nodeToExpr(right)))
    case Token(">", left :: right:: Nil) => GreaterThan(nodeToExpr(left), nodeToExpr(right))
    case Token(">=", left :: right:: Nil) => GreaterThanOrEqual(nodeToExpr(left), nodeToExpr(right))
    case Token("<", left :: right:: Nil) => LessThan(nodeToExpr(left), nodeToExpr(right))
    case Token("<=", left :: right:: Nil) => LessThanOrEqual(nodeToExpr(left), nodeToExpr(right))
    case Token("LIKE", left :: right:: Nil) => UnresolvedFunction("LIKE", Seq(nodeToExpr(left), nodeToExpr(right)))
    case Token("RLIKE", left :: right:: Nil) => UnresolvedFunction("RLIKE", Seq(nodeToExpr(left), nodeToExpr(right)))
    case Token("REGEXP", left :: right:: Nil) => UnresolvedFunction("REGEXP", Seq(nodeToExpr(left), nodeToExpr(right)))
    case Token("TOK_FUNCTION", Token("TOK_ISNOTNULL", Nil) :: child :: Nil) => IsNotNull(nodeToExpr(child))
    case Token("TOK_FUNCTION", Token("TOK_ISNULL", Nil) :: child :: Nil) => IsNull(nodeToExpr(child))

    /* Boolean Logic */
    case Token(AND(), left :: right:: Nil) => And(nodeToExpr(left), nodeToExpr(right))
    case Token(OR(), left :: right:: Nil) => Or(nodeToExpr(left), nodeToExpr(right))
    case Token(NOT(), child :: Nil) => Not(nodeToExpr(child))

    /* Other functions */
    case Token("TOK_FUNCTION", Token(RAND(), Nil) :: Nil) => Rand

    /* UDFs - Must be last otherwise will preempt built in functions */
    case Token("TOK_FUNCTION", Token(name, Nil) :: args) =>
      UnresolvedFunction(name, args.map(nodeToExpr))

    /* Literals */
    case Token("TOK_NULL", Nil) => Literal(null, IntegerType) // TODO: What type is null?
    case Token(TRUE(), Nil) => Literal(true, BooleanType)
    case Token(FALSE(), Nil) => Literal(false, BooleanType)
    case Token("TOK_STRINGLITERALSEQUENCE", strings) =>
      Literal(strings.map(s => BaseSemanticAnalyzer.unescapeSQLString(s.asInstanceOf[ASTNode].getText)).mkString)

    // This code is adapted from https://github.com/apache/hive/blob/branch-0.10/ql/src/java/org/apache/hadoop/hive/ql/parse/TypeCheckProcFactory.java#L223
    case ast: ASTNode if numericAstTypes contains ast.getType() =>
      var v: Literal = null
      try {
        if (ast.getText().endsWith("L")) {
          // Literal bigint.
          v = Literal(ast.getText().substring(0, ast.getText().length() - 1).toLong, LongType)
        } else if (ast.getText().endsWith("S")) {
          // Literal smallint.
          v = Literal(ast.getText().substring(0, ast.getText().length() - 1).toShort, ShortType)
        } else if (ast.getText().endsWith("Y")) {
          // Literal tinyint.
          v = Literal(ast.getText().substring(0, ast.getText().length() - 1).toByte, ByteType)
        } else if (ast.getText().endsWith("BD")) {
          throw new NotImplementedError("Hive Decimal not implemented yet")
          /* TODO: Implement!
          // Literal decimal
          val strVal = ast.getText().substring(0, ast.getText().length() - 2);
          HiveDecimal hd = HiveDecimal.create(strVal);
          int prec = 1;
          int scale = 0;
          if (hd != null) {
            prec = hd.precision();
            scale = hd.scale();
          }
          DecimalTypeInfo typeInfo = TypeInfoFactory.getDecimalTypeInfo(prec, scale);
          return new ExprNodeConstantDesc(typeInfo, strVal);   */
        } else {
          v = Literal(ast.getText().toDouble, DoubleType)
          v = Literal(ast.getText().toLong, LongType)
          v = Literal(ast.getText().toInt, IntegerType)
        }
      } catch {
        case nfe: NumberFormatException => // Do nothing
      }

      if (v == null)
        sys.error(s"Failed to parse number ${ast.getText}")
      else
        v

    case ast: ASTNode if ast.getType == HiveParser.StringLiteral =>
      Literal(BaseSemanticAnalyzer.unescapeSQLString(ast.getText))

    case a: ASTNode =>
      throw new NotImplementedError(
        s"No parse rules for ASTNode type: ${a.getType}, text: ${a.getText} :\n ${dumpTree(a).toString}")
  }

  def dumpTree(node: Node, builder: StringBuilder = new StringBuilder, indent: Int = 0)
  : StringBuilder = {
    node match {
      case a: ASTNode => builder.append(("  " * indent) + a.getText + "\n")
      case other => sys.error(s"Non ASTNode encountered: $other")
    }

    Option(node.getChildren).map(_.toList).getOrElse(Nil).foreach(dumpTree(_, builder, indent + 1))
    builder
  }
}