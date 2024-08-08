// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules/';
import { SvNodeState } from '@daml.js/splice-dso-governance/lib/Splice/DSO/SvState';
import { DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

import { Contract, PollingStrategy } from '../../../utils';
import { SvUiState } from '../../components';
import { useScanClient } from './ScanClientContext';

export const useDsoInfo = (): UseQueryResult<SvUiState> => {
  const scanClient = useScanClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['getDsoInfo', DsoRules, AmuletRules],
    queryFn: async () => {
      const resp = await scanClient.getDsoInfo();
      return {
        svUser: resp.sv_user,
        svPartyId: resp.sv_party_id,
        dsoPartyId: resp.dso_party_id,
        votingThreshold: resp.voting_threshold,
        amuletRules: Contract.decodeOpenAPI(resp.amulet_rules, AmuletRules),
        dsoRules: Contract.decodeOpenAPI(resp.dso_rules, DsoRules),
        nodeStates: resp.sv_node_states.map(c => Contract.decodeOpenAPI(c, SvNodeState)),
      };
    },
  });
};

export default useDsoInfo;
