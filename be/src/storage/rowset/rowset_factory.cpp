// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/olap/rowset/rowset_factory.cpp

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "storage/rowset/rowset_factory.h"

#include <memory>

#include "gen_cpp/olap_file.pb.h"
#include "rowset.h"
#include "runtime/exec_env.h"
#include "storage/rowset/beta_rowset_writer.h"
#include "storage/rowset/rowset_writer.h"

namespace starrocks {

Status RowsetFactory::create_rowset(const TabletSchema* schema, const std::string& rowset_path,
                                    const RowsetMetaSharedPtr& rowset_meta, RowsetSharedPtr* rowset) {
    *rowset = Rowset::create(ExecEnv::GetInstance()->metadata_mem_tracker(), schema, rowset_path, rowset_meta);
    RETURN_IF_ERROR((*rowset)->init());
    return Status::OK();
}

Status RowsetFactory::create_rowset_writer(const RowsetWriterContext& context, std::unique_ptr<RowsetWriter>* output) {
    if (context.writer_type == kHorizontal) {
        *output = std::make_unique<HorizontalBetaRowsetWriter>(context);
    } else {
        DCHECK(context.writer_type == kVertical);
        *output = std::make_unique<VerticalBetaRowsetWriter>(context);
    }
    return (*output)->init();
}

} // namespace starrocks
