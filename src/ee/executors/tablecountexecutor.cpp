/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

#include <iostream>
#include "tablecountexecutor.h"
#include "common/debuglog.h"
#include "common/common.h"
#include "common/tabletuple.h"
#include "common/FatalException.hpp"
#include "common/ValueFactory.hpp"
#include "expressions/abstractexpression.h"
#include "plannodes/tablecountnode.h"
#include "storage/persistenttable.h"
#include "storage/temptable.h"
#include "storage/tablefactory.h"
#include "storage/tableiterator.h"

using namespace voltdb;

bool TableCountExecutor::p_init(AbstractPlanNode* abstract_node,
                             TempTableLimits* limits)
{
    VOLT_TRACE("init Table Count Executor");

    assert(dynamic_cast<TableCountPlanNode*>(abstract_node));
    assert(dynamic_cast<TableCountPlanNode*>(abstract_node)->getTargetTable());

    assert(abstract_node->getOutputSchema().size() == 1);

    // Create output table based on output schema from the plan
    setTempOutputTable(limits);
    return true;
}

bool TableCountExecutor::p_execute(const NValueArray &params) {
    TableCountPlanNode* node = dynamic_cast<TableCountPlanNode*>(m_abstractNode);
    assert(node);
    Table* output_table = node->getOutputTable();
    assert(output_table);
    assert ((int)output_table->columnCount() == 1);

    PersistentTable* target_table = dynamic_cast<PersistentTable*>(node->getTargetTable());
    if ( ! target_table) {
        throw SerializableEEException(VOLT_EE_EXCEPTION_TYPE_EEEXCEPTION,
                                      "May not iterate a streamed table.");
    }
    VOLT_DEBUG("Table Count table : %s which has %d active, %d visible, %d allocated",
               target_table->name().c_str(),
               (int)target_table->activeTupleCount(),
               (int)target_table->visibleTupleCount(),
               (int)target_table->allocatedTupleCount());

    assert (node->getPredicate() == NULL);

    TableTuple& tmptup = output_table->tempTuple();
    tmptup.setNValue(0, ValueFactory::getBigIntValue(target_table->visibleTupleCount()));
    output_table->insertTuple(tmptup);


    //printf("Table count answer: %d", iterator.getSize());
    //printf("\n%s\n", output_table->debug().c_str());
    VOLT_TRACE("\n%s\n", output_table->debug().c_str());
    VOLT_DEBUG("Finished Table Counting");

    return true;
}

TableCountExecutor::~TableCountExecutor() {
}

