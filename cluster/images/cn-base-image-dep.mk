# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

$(dir)/$(docker-build) : $(cn-image)/$(docker-build)
$(dir)/$(docker-push) : $(cn-image)/$(docker-push)
