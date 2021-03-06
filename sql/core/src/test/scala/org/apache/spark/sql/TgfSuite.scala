/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql
package execution

import org.scalatest.{BeforeAndAfterAll, FunSuite}

import catalyst.analysis._
import catalyst.expressions._
import catalyst.plans._
import catalyst.plans.logical.LogicalPlan
import catalyst.types._

import TestSQLContext._

/**
 * This is an example TGF that uses UnresolvedAttributes 'name and 'age to access specific columns
 * from the input data.  These will be replaced during analysis with specific AttributeReferences
 * and then bound to specific ordinals during query planning. While TGFs could also access specific
 * columns using hand-coded ordinals, doing so violates data independence.
 *
 * Note: this is only a rough example of how TGFs can be expressed, the final version will likely
 * involve a lot more sugar for cleaner use in Scala/Java/etc.
 */
case class ExampleTGF(input: Seq[Attribute] = Seq('name, 'age)) extends Generator {
  def children = input
  protected def makeOutput() = 'nameAndAge.string :: Nil

  val Seq(nameAttr, ageAttr) = input

  override def apply(input: Row): TraversableOnce[Row] = {
    val name = nameAttr.apply(input)
    val age = ageAttr.apply(input).asInstanceOf[Int]

    Iterator(
      new GenericRow(Array[Any](s"$name is $age years old")),
      new GenericRow(Array[Any](s"Next year, $name will be ${age + 1} years old")))
  }
}

class TgfSuite extends DslQueryTest {
  val inputData =
    logical.LocalRelation('name.string, 'age.int).loadData(
      ("michael", 29) :: Nil
    )

  test("simple tgf example") {
    checkAnswer(
      inputData.generate(ExampleTGF()),
      Seq(
        "michael is 29 years old" :: Nil,
        "Next year, michael will be 30 years old" :: Nil))
  }
}
