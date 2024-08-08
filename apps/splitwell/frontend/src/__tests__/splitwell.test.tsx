// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { rest } from 'msw';
import {
  ListAcceptedGroupInvitesResponse,
  ListBalanceUpdatesResponse,
  ListGroupInvitesResponse,
} from 'splitwell-openapi';
import { test, expect, describe } from 'vitest';

import App from '../App';
import {
  alicePartyId,
  bobPartyId,
  groupName,
  splitwellDomainId,
  splitwellProviderPartyId,
} from './mocks/constants';
import { makeAcceptedGroupInvite, makeBalanceUpdate, makeGroupInvite } from './mocks/templates';
import { server } from './setup/setup';

describe('alice can', () => {
  test('login and see her party ID', async () => {
    const user = userEvent.setup();
    render(<App />);

    const input = screen.getByRole('textbox');
    await user.type(input, 'alice_wallet_user');

    const button = screen.getByRole('button', { name: 'Log In' });
    await user.click(button);

    await expect(screen.findByDisplayValue(alicePartyId)).resolves.toBeDefined();
  });

  test('submit a group create request', async () => {
    const user = userEvent.setup();
    render(<App />);

    const input = await screen.findByRole('textbox', { name: 'Group ID' });
    await user.type(input, groupName);

    const button = screen.getByRole('button', { name: 'Create Group' });
    await user.click(button);
  });

  test('see a group', async () => {
    render(<App />);

    await expect(screen.findByText(groupName)).resolves.toBeDefined();
  });

  test('request a membership invite', async () => {
    const user = userEvent.setup();
    render(<App />);

    await expect(screen.findByText(groupName)).resolves.toBeDefined();

    const createInviteButton = screen.getByRole('button', { name: 'Create Invite' });
    await user.click(createInviteButton);

    const fakeGroupInvite = makeGroupInvite(splitwellProviderPartyId, alicePartyId, groupName);

    server.use(
      rest.get(`${window.splice_config.services.splitwell.url}/group-invites`, (_, res, ctx) => {
        return res(
          ctx.json<ListGroupInvitesResponse>({
            group_invites: [
              {
                contract: fakeGroupInvite,
                domain_id: splitwellDomainId,
              },
            ],
          })
        );
      })
    );

    const copyInviteButton = await screen.findByRole('button', { name: 'Copy invite' });
    await user.click(copyInviteButton);

    await expect(window.navigator.clipboard.readText()).resolves.toBe(
      `{"templateId":"cbca8a4f8d6170f38cd7a5c9cc0371cc3ccb4fb5bf5daf0702aa2c3849ac6bde:Splice.Splitwell:GroupInvite","contractId":"008a4f445f23361cf92ffd48bf8556429921060a40c7169dc11c5a28717d7750e3ca021220bcce6356513ce1790a1c525f5e7709be50336235d2c08be698a581a4e2bc2c6d","payload":{"group":{"owner":"${alicePartyId}","dso":"DSO::122065980b045703ed871be9b93afb28b61c874b667434259d1df090096837e3ffd0","members":[],"id":{"unpack":"${groupName}"},"provider":"${splitwellProviderPartyId}","acceptDuration":{"microseconds":"300000000"}}},"createdEventBlob":"","createdAt":"2023-10-06T13:24:12.679640Z","domainId":"${splitwellDomainId}"}`
    );
  });

  test('see pending membership request', async () => {
    render(<App />);

    server.use(
      rest.get(
        `${window.splice_config.services.splitwell.url}/accepted-group-invites`,
        (_, res, ctx) => {
          return res(
            ctx.json<ListAcceptedGroupInvitesResponse>({
              accepted_group_invites: [
                makeAcceptedGroupInvite(
                  splitwellProviderPartyId,
                  alicePartyId,
                  bobPartyId,
                  groupName
                ),
              ],
            })
          );
        }
      )
    );

    await expect(screen.findByRole('button', { name: 'Add' })).resolves.toBeDefined();
  });

  test('view balance updates', async () => {
    render(<App />);

    server.use(
      rest.get(`${window.splice_config.services.splitwell.url}/balance-updates`, (_, res, ctx) => {
        return res(
          ctx.json<ListBalanceUpdatesResponse>({
            balance_updates: [
              makeBalanceUpdate(
                splitwellProviderPartyId,
                alicePartyId,
                groupName,
                {
                  tag: 'ExternalPayment',
                  value: {
                    payer: alicePartyId,
                    description: 'expenses',
                    amount: '30.0',
                  },
                },
                'cid3'
              ),
              makeBalanceUpdate(
                splitwellProviderPartyId,
                alicePartyId,
                groupName,
                {
                  tag: 'Transfer',
                  value: {
                    sender: bobPartyId,
                    receiver: alicePartyId,
                    amount: '40.0',
                  },
                },
                'cid2'
              ),
              makeBalanceUpdate(
                splitwellProviderPartyId,
                alicePartyId,
                groupName,
                {
                  tag: 'ExternalPayment',
                  value: {
                    payer: alicePartyId,
                    description: 'dinner',
                    amount: '15.0',
                  },
                },
                'cid1'
              ),
            ],
          })
        );
      })
    );

    // eslint-disable-next-line testing-library/no-node-access
    const balanceUpdates = (await screen.findByText('Balance Updates')).parentElement; // testing-library doesn't provide any functions for accessing the parent element, so use direct node access
    expect(balanceUpdates).toBeDefined();

    const balanceUpdatesList = within(balanceUpdates!).getAllByRole('listitem');

    expect(balanceUpdatesList.length).toBe(3);

    await expect(
      within(balanceUpdatesList[0]).findByText('alice.unverified.tns')
    ).resolves.toBeDefined();
    await expect(
      within(balanceUpdatesList[0]).findByText('paid 30.0 TLM for expenses')
    ).resolves.toBeDefined();

    await expect(
      within(balanceUpdatesList[1]).findByText('bob.unverified.tns')
    ).resolves.toBeDefined();
    await expect(
      within(balanceUpdatesList[1]).findByText('sent 40.0 TLM to')
    ).resolves.toBeDefined();

    await expect(
      within(balanceUpdatesList[2]).findByText('alice.unverified.tns')
    ).resolves.toBeDefined();
    await expect(
      within(balanceUpdatesList[2]).findByText('paid 15.0 TLM for dinner')
    ).resolves.toBeDefined();
  });
});
