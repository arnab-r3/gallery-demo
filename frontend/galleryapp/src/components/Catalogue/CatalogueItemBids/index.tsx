import styles from "./styles.module.scss";
import { Bid } from "@Models";
import { Badge, Button } from "@r3/r3-tooling-design-system";

interface Props {
  bids: Bid[];
  lotId: string;
  open: boolean;
}

function getStatus(bidAccepted: boolean, biddingOpen: boolean) {
  if (bidAccepted) {
    return <Badge variant="green">Accepted</Badge>;
  }
  if (!biddingOpen && !bidAccepted) {
    return <Badge variant="red">Unsuccessful</Badge>;
  }

  return <Badge variant="gray">Open</Badge>;
}

function CatalogueItemBids({ bids, open }: Props) {
  return (
    <tr className={styles.main}>
      <td colSpan={6} className={styles.bidsRow}>
        <div>
          {!bids.length ? (
            <h6>No bids have been placed yet.</h6>
          ) : (
            <table>
              <thead className={styles.tableHead}>
                <tr>
                  <th>Patron</th>
                  <th>Bid value</th>
                  <th>Network</th>
                  <th>Expires in</th>
                  <th>Status</th>
                  {open ? <th /> : null}
                </tr>
              </thead>
              <tbody>
                {bids.map((bid) => (
                  <tr
                    key={bid.cordaReference}
                    className={!bid.accepted && !open ? styles.bidRejected : ""}
                  >
                    <td>{bid.bidderDisplayName}</td>
                    <td>
                      {bid.accepted ? (
                        <Badge variant="green">
                          {bid.amount} {bid.currencyCode}
                        </Badge>
                      ) : (
                        `${bid.amount} ${bid.currencyCode}`
                      )}
                    </td>
                    <td>
                      <Badge variant="gray">{bid.notary}</Badge>
                    </td>
                    <td>{bid.expiryDate}</td>
                    <td>{getStatus(bid.accepted, open)}</td>
                    {open ? (
                      <td>
                        <Button size="small" variant="tertiary">
                          Accept
                        </Button>
                      </td>
                    ) : null}
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </td>
    </tr>
  );
}

export default CatalogueItemBids;
