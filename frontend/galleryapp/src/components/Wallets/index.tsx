import styles from "./styles.module.scss";
import WalletItem from "@Components/Wallets/WalletItem";
import { Participant } from "@Models";
import { useContext } from "react";
import { WalletsContext } from "@Context/wallets";

interface Props {
  user: Participant;
}

function Wallets({ user }: Props) {
  const { getWalletsByUser } = useContext(WalletsContext);
  const wallets = getWalletsByUser(user);
  const userX500 = user.networkIds[0] ? user.networkIds[0].x500 : "";

  return (
    <section className={styles.main}>
      <h3>Balances</h3>
      <table>
        <thead>
          <tr>
            <th />
            <th>Asset Type</th>
            <th>Encumbered</th>
            <th>Total</th>
          </tr>
        </thead>
        <tbody>
          {wallets.map((wallet) => (
            <WalletItem key={wallet.currencyCode} wallet={wallet} x500={userX500} />
          ))}
        </tbody>
      </table>
    </section>
  );
}

export default Wallets;
