package com.alecstrong.sql.psi.core.psi.mixins

import com.alecstrong.sql.psi.core.ModifiableFileLazy
import com.alecstrong.sql.psi.core.psi.QueryElement.QueryResult
import com.alecstrong.sql.psi.core.psi.SqlCompositeElementImpl
import com.alecstrong.sql.psi.core.psi.SqlJoinClause
import com.alecstrong.sql.psi.core.psi.SqlJoinConstraint
import com.alecstrong.sql.psi.core.psi.SqlJoinOperator
import com.alecstrong.sql.psi.core.psi.SqlTypes
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.tree.TokenSet

internal abstract class JoinClauseMixin(
  node: ASTNode,
) : SqlCompositeElementImpl(node),
  SqlJoinClause {
  override fun queryAvailable(child: PsiElement): Collection<QueryResult> {
    if (child is SqlJoinConstraint) {
      val queryAvailable = tableOrSubqueryList[0].queryExposed()
        .map { it.copy(adjacent = true) }
        .plus(super.queryAvailable(child).map { it.copy(adjacent = false) })
        .toMutableList()
      tableOrSubqueryList.drop(1).zip(joinConstraintList)
        .forEach { (subquery, constraint) ->
          if (child == constraint) {
            if (child.node.findChildByType(
                SqlTypes.USING,
              ) != null
            ) {
              return listOf(
                QueryResult(
                  null,
                  queryAvailable.flatMap { it.columns }
                    .filter { (column, _) ->
                      column is PsiNamedElement && column.name!! in subquery.queryExposed()
                        .flatMap { it.columns }
                        .mapNotNull { (it.element as? PsiNamedElement)?.name }
                    }
                    .distinctBy { (it.element as? PsiNamedElement)?.name ?: it },
                ),
              )
            }
            return queryAvailable + subquery.queryExposed()
          }
          queryAvailable += subquery.queryExposed()
        }
      return queryExposed()
    }
    return super.queryAvailable(child)
  }

  private val queryExposed = ModifiableFileLazy {
    val queryAvailable = mutableListOf<QueryResult>()

    fun queryResult(query: Collection<QueryResult>, isOuterJoin: Boolean, constraint: SqlJoinConstraint): QueryResult {
      var columns = query.flatMap { it.columns }
      var synthesizedColumns = query.flatMap { it.synthesizedColumns }

      if (isOuterJoin) {
        columns = columns.map { it.copy(nullable = true) }
        synthesizedColumns = synthesizedColumns.map { it.copy(nullable = true) }
      }
      if (constraint.node?.findChildByType(
          SqlTypes.USING,
        ) != null
      ) {
        val columnNames = constraint.columnNameList.map { it.name }
        columns = columns.map {
          it.copy(hiddenByUsing = it.element is PsiNamedElement && it.element.name in columnNames)
        }
      }
      return QueryResult(
        table = query.first().table,
        columns = columns,
        synthesizedColumns = synthesizedColumns,
        joinConstraint = constraint,
      )
    }

    tableOrSubqueryList.drop(1)
      .zip(joinConstraintList)
      .zip(joinOperatorList) zip2@{ (subquery, constraint), operator ->
        queryAvailable += tableOrSubqueryList.first().queryExposed().let { query ->
          if (query.isNotEmpty()) queryResult(query, supportsRightJoin(operator), constraint) else return@zip2
        }
        queryAvailable += subquery.queryExposed().let { query ->
          if (query.isNotEmpty()) queryResult(query, supportsLeftJoin(operator), constraint) else return@zip2
        }
      }
    return@ModifiableFileLazy queryAvailable
  }

  private fun supportsRightJoin(operator: SqlJoinOperator): Boolean {
    return operator.node.findChildByType(
      TokenSet.create(
        SqlTypes.RIGHT_JOIN_OPERATOR,
        SqlTypes.FULL_JOIN_OPERATOR,
      ),
    ) != null
  }

  private fun supportsLeftJoin(operator: SqlJoinOperator): Boolean {
    return operator.node.findChildByType(
      TokenSet.create(
        SqlTypes.LEFT_JOIN_OPERATOR,
        SqlTypes.FULL_JOIN_OPERATOR,
      ),
    ) != null
  }

  override fun queryExposed() = queryExposed.forFile(containingFile)
}
