/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.gui.main.dao.wallet.send;

import com.google.common.util.concurrent.FutureCallback;
import io.bitsquare.app.DevFlags;
import io.bitsquare.btc.InsufficientFundsException;
import io.bitsquare.btc.exceptions.TransactionVerificationException;
import io.bitsquare.btc.exceptions.WalletException;
import io.bitsquare.btc.provider.fee.FeeService;
import io.bitsquare.btc.wallet.BtcWalletService;
import io.bitsquare.btc.wallet.SquWalletService;
import io.bitsquare.common.util.MathUtils;
import io.bitsquare.gui.common.view.ActivatableView;
import io.bitsquare.gui.common.view.FxmlView;
import io.bitsquare.gui.components.InputTextField;
import io.bitsquare.gui.main.dao.wallet.BalanceUtil;
import io.bitsquare.gui.main.overlays.popups.Popup;
import io.bitsquare.gui.util.BSFormatter;
import io.bitsquare.gui.util.Layout;
import io.bitsquare.gui.util.SQUFormatter;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.bitcoinj.core.*;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static io.bitsquare.gui.util.FormBuilder.*;

@FxmlView
public class TokenSendView extends ActivatableView<GridPane, Void> {


    private TextField balanceTextField;

    private final SquWalletService squWalletService;
    private final BtcWalletService btcWalletService;
    private final FeeService feeService;
    private final BSFormatter squFormatter;
    private BSFormatter btcFormatter;
    private BalanceUtil balanceUtil;

    @Nullable
    private Wallet squWallet;
    private int gridRow = 0;
    private InputTextField amountInputTextField;
    private Button sendButton;
    private InputTextField receiversAddressInputTextField;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private TokenSendView(SquWalletService squWalletService, BtcWalletService btcWalletService, FeeService feeService, SQUFormatter squFormatter, BSFormatter btcFormatter, BalanceUtil balanceUtil) {
        this.squWalletService = squWalletService;
        this.btcWalletService = btcWalletService;
        this.feeService = feeService;

        this.squFormatter = squFormatter;
        this.btcFormatter = btcFormatter;
        this.balanceUtil = balanceUtil;
    }

    @Override
    public void initialize() {
        addTitledGroupBg(root, gridRow, 1, "Balance");
        balanceTextField = addLabelTextField(root, gridRow, "SQU balance:", Layout.FIRST_ROW_DISTANCE).second;
        balanceUtil.setBalanceTextField(balanceTextField);
        balanceUtil.initialize();

        addTitledGroupBg(root, ++gridRow, 3, "Send funds", Layout.GROUP_DISTANCE);
        amountInputTextField = addLabelInputTextField(root, gridRow, "Amount in SQU:", Layout.FIRST_ROW_AND_GROUP_DISTANCE).second;
        amountInputTextField.setPromptText("Set amount to withdraw (min. amount is 547");

        receiversAddressInputTextField = addLabelInputTextField(root, ++gridRow, "Receiver's address:").second;
        receiversAddressInputTextField.setPromptText("Fill in your destination address");

        sendButton = addButtonAfterGroup(root, ++gridRow, "Send SQU funds");

        if (DevFlags.DEV_MODE) {
            amountInputTextField.setText("0.546"); // 546 is dust limit
            receiversAddressInputTextField.setText("mgJE2Fq7UB12mvqBF16GEVotQGmCV7WwQE");
        }

        sendButton.setOnAction((event) -> {
            String receiversAddressString = receiversAddressInputTextField.getText();
            Coin receiverAmount = squFormatter.parseToCoin(amountInputTextField.getText());
            try {
                Transaction preparedSendTx = squWalletService.getPreparedSendTx(receiversAddressString, receiverAmount);
                Transaction txWithBtcFee = btcWalletService.completePreparedSquTx(preparedSendTx, true, null);
                Transaction signedTx = squWalletService.signTx(txWithBtcFee);

                Coin miningFee = signedTx.getFee();
                int txSize = signedTx.bitcoinSerialize().length;
                new Popup().headLine("Confirm withdrawal request")
                        .confirmation("Sending: " + squFormatter.formatCoinWithCode(receiverAmount) + "\n" +
                               /* "From address: " + withdrawFromTextField.getText() + "\n" +*/
                                "Receiver address: " + receiversAddressString + "\n" +
                                "Transaction fee: " + btcFormatter.formatCoinWithCode(miningFee) + " (" +
                                MathUtils.roundDouble(((double) miningFee.value / (double) txSize), 2) +
                                " Satoshis/byte)\n" +
                                "Transaction size: " + (txSize / 1000d) + " Kb\n\n" +
                                /*"The recipient will receive: " + squFormatter.formatCoinWithCode(receiverAmount) + "\n\n" +*/
                                "Are you sure you want to withdraw that amount?")
                        .actionButtonText("Yes")
                        .onAction(() -> {
                            try {
                                squWalletService.commitTx(txWithBtcFee);
                                // We need to create another instance, otherwise the tx would trigger an invalid state exception 
                                // if it gets committed 2 times 
                                btcWalletService.commitTx(btcWalletService.getClonedTransaction(txWithBtcFee));
                                squWalletService.broadcastTx(signedTx, new FutureCallback<Transaction>() {
                                    @Override
                                    public void onSuccess(@Nullable Transaction transaction) {
                                        if (transaction != null) {
                                            log.error("Successfully sent tx with id " + transaction.getHashAsString());
                                        }
                                    }

                                    @Override
                                    public void onFailure(@NotNull Throwable t) {
                                        log.error(t.toString());
                                        new Popup<>().warning(t.toString());
                                    }
                                });
                            } catch (WalletException | TransactionVerificationException e) {
                                log.error(e.toString());
                                e.printStackTrace();
                                new Popup<>().warning(e.toString());
                            }
                        })
                        .closeButtonText("Cancel")
                        .show();
            } catch (AddressFormatException | InsufficientFundsException |
                    TransactionVerificationException | WalletException | InsufficientMoneyException e) {
                log.error(e.toString());
                e.printStackTrace();
                new Popup<>().warning(e.toString());
            }
        });
    }

    @Override
    protected void activate() {
        balanceUtil.activate();
        this.squWallet = squWalletService.getWallet();
    }

    @Override
    protected void deactivate() {
        balanceUtil.deactivate();
    }
}
