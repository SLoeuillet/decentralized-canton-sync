// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';
import { GetTopValidatorsByValidatorRewardsResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';
import useGetRoundOfLatestData from './useGetRoundOfLatestData';

const useGetTopValidatorsByValidatorRewards =
  (): UseQueryResult<GetTopValidatorsByValidatorRewardsResponse> => {
    const scanClient = useScanClient();
    const latestRoundQuery = useGetRoundOfLatestData(PollingStrategy.FIXED);
    const latestRoundNumber = latestRoundQuery.data?.round;

    return useQuery({
      refetchInterval: PollingStrategy.FIXED,
      queryKey: ['scan-api', 'getTopValidatorsByValidatorRewards', latestRoundNumber],
      queryFn: async () => scanClient.getTopValidatorsByValidatorRewards(latestRoundNumber!, 10),
      enabled: latestRoundNumber !== undefined, // include round 0 as valid,
    });
  };

export default useGetTopValidatorsByValidatorRewards;
