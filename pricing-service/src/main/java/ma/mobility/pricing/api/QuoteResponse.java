package ma.mobility.pricing.api;

public class QuoteResponse {
    public long subtotalMinor;
    public long feesMinor;
    public long discountMinor;
    public long totalMinor;
    public String currency;
    public String rule;

    public QuoteResponse(long subtotalMinor, long feesMinor, long discountMinor, long totalMinor,
                         String currency, String rule) {
        this.subtotalMinor = subtotalMinor;
        this.feesMinor = feesMinor;
        this.discountMinor = discountMinor;
        this.totalMinor = totalMinor;
        this.currency = currency;
        this.rule = rule;
    }
}
