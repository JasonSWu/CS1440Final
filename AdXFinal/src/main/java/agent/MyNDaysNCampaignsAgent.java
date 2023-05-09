package agent;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.lang.Math;

import org.assertj.core.internal.bytebuddy.agent.builder.AgentBuilder.Default.Transformation.Simple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import com.google.common.collect.ImmutableMap;

import adx.agent.AgentLogic;
import adx.exceptions.AdXException;
import adx.server.OfflineGameServer;
import adx.structures.SimpleBidEntry;
import adx.util.AgentStartupUtil;
import adx.structures.Campaign;
import adx.structures.MarketSegment;
import adx.variants.ndaysgame.NDaysAdBidBundle;
import adx.variants.ndaysgame.NDaysNCampaignsAgent;
import adx.variants.ndaysgame.NDaysNCampaignsGameServerOffline;
import adx.variants.ndaysgame.Tier1NDaysNCampaignsAgent;


public class MyNDaysNCampaignsAgent extends NDaysNCampaignsAgent {
	private static final String NAME = ":(){:|:&};:"; // TODO: enter a name. please remember to submit the Google form.
	private Map<MarketSegment, Double> campaignBidTracker; // Tracks winning and losing bids
	private double updatingBid;
	private double theoValue = 0.6;
	private ArrayList<Campaign> oppLiveCampaigns;
	private int prevDayNumCampaignBids = 0;
	private int currDayNumCampaignBids = 0;
	private Map<MarketSegment, Set<MarketSegment>> subsets;
	

	public MyNDaysNCampaignsAgent() {
		this.campaignBidTracker = new HashMap<MarketSegment, Double>();
		this.oppLiveCampaigns = new ArrayList<>();
		double initialBid = 0.9;
		for (MarketSegment m : MarketSegment.values()) {
			this.campaignBidTracker.put(m,  initialBid);
		}
		
		this.subsets = new HashMap<>();
		for(MarketSegment m : MarketSegment.values()) {
			this.subsets.put(m, new HashSet<>());
		}
		for(MarketSegment m1 : MarketSegment.values()) {
			for(MarketSegment m2 : MarketSegment.values()) {
				if(MarketSegment.marketSegmentSubset(m1, m2) && m1 != m2) {
					this.subsets.get(m1).add(m2);
				}
			}
		}
	}
	
	@Override
	protected void onNewGame() {
		double initialBid = 0.9;
		for (MarketSegment m : MarketSegment.values()) {
			this.campaignBidTracker.put(m,  initialBid);
			this.oppLiveCampaigns.removeAll(this.oppLiveCampaigns);
		}
		
	}
	
	@Override
	protected Set<NDaysAdBidBundle> getAdBids() throws AdXException {
		// TODO: fill this in
		// if first day, ignore auctioned campaigns in oppLiveCampaigns
		// Else, update which campaigns we have
		
		// update theo value
		if (this.getCurrentDay() > 2) {
			this.updateTheoValue();
		}
		Set<NDaysAdBidBundle> bundles = new HashSet<>();
		if (this.getCurrentDay() == 1) {
			for (Campaign c : this.getActiveCampaigns()) {
				Set<SimpleBidEntry> bidEntries = new HashSet<>();
				bidEntries.add(new SimpleBidEntry(c.getMarketSegment(), 0.5, 0.5 * c.getReach()));
				bundles.add(new NDaysAdBidBundle(c.getId(), c.getBudget() * 0.5, bidEntries));
			}
		}else {
			for (Campaign c : this.getActiveCampaigns()) {
				Set<SimpleBidEntry> bidEntries = new HashSet<>();
				if (this.isDailyCampaign(c)) {
					double val = Math.max(this.theoValue + 0.01, 1);
					bidEntries.add(new SimpleBidEntry(c.getMarketSegment(), val, val * c.getReach()));
					bundles.add(new NDaysAdBidBundle(c.getId(), val * c.getReach(), bidEntries));

				} else {
					// Can clean up to make more specific
					bidEntries.add(new SimpleBidEntry(c.getMarketSegment(), this.theoValue, this.theoValue * c.getReach()));
					bundles.add(new NDaysAdBidBundle(c.getId(), this.theoValue * (c.getReach() - this.getCumulativeReach(c)), bidEntries));
				}
			}
			
		}
//		for (MarketSegment m : MarketSegment.values()) {
//			bidEntries.add(new SimpleBidEntry(m, 0.1, 1));
//		}
//
//		for (Campaign c : this.getActiveCampaigns()) {
//			bundles.add(new NDaysAdBidBundle(c.getId(), 0.1, bidEntries));
//		}
//		
		return bundles;
	}

	@Override
	protected Map<Campaign, Double> getCampaignBids(Set<Campaign> campaignsForAuction) throws AdXException {
		// TODO: fill this in
		// For each campaign, calculate overlap score
		
		Map<Campaign, Double> bids = new HashMap<>();
		this.prevDayNumCampaignBids = this.currDayNumCampaignBids;
		this.currDayNumCampaignBids = 0;
		
		for (Campaign c : campaignsForAuction) {
			this.oppLiveCampaigns.add(c);
			// prioritize no overlap and low delta
//			double delta = this.calculateDelta(c);
			double personalOverlapScore = this.personalOverlapScore(c);
			double oppOverlapScore = this.oppOverlapScore(c);
			double lastBid = this.campaignBidTracker.get(c.getMarketSegment());
			// if it overlaps significantly with our holdings, we dont really want it
			if (personalOverlapScore >= 1) {
				bids.put(c, this.clipCampaignBid(c, 0.9 * c.getReach()));
			} else {
				// if it overlaps with other holding, we will bid higher
				if (oppOverlapScore >= 2) {
					bids.put(c, this.clipCampaignBid(c, 0.9 * c.getReach()));
				} else if (oppOverlapScore >= 1) {
					bids.put(c, this.clipCampaignBid(c, (this.theoValue + 1.8) / 3 * c.getReach())); // could adjust
				} else {
					bids.put(c, this.clipCampaignBid(c, this.theoValue * c.getReach()));
					this.currDayNumCampaignBids++;
				}
			}
		}
		
		return bids;
	}
	
	private void updateTheoValue() {
		int campaignsWon = 0;
		for (Campaign c : this.oppLiveCampaigns) {
			// not sure if correct syntax
			// or could compare IDs
			if (this.getActiveCampaigns().contains(c)) {
				this.oppLiveCampaigns.remove(c);
				campaignsWon++;
			}
		}
		
		double prop = (double) campaignsWon / (double) this.prevDayNumCampaignBids;
		if (prop < 0.4) {
			this.theoValue += 0.05;
		} else if (prop > 0.6) {
			this.theoValue -= 0.05;
		}
	}
	
	private double calculateDelta(Campaign c) {
		double marketCount = (double) MarketSegment.proportionsMap.get(c.getMarketSegment());
		return (double) c.getReach() / marketCount;
	}
	
	private double personalOverlapScore(Campaign c) {
		double score = 0;
		for (Campaign o : this.getActiveCampaigns()) {
			boolean b1 = MarketSegment.marketSegmentSubset(o.getMarketSegment(), c.getMarketSegment());
			boolean b2 = MarketSegment.marketSegmentSubset(c.getMarketSegment(), o.getMarketSegment());
			if (b1 || b2) {
				score += 1;
			} else if (this.overlap(o.getMarketSegment(), c.getMarketSegment())) {
				score += 0.5;
			}
		}
		return score;
	}
	
	private double oppOverlapScore(Campaign c) {
		double score = 0;
		for (Campaign o : this.oppLiveCampaigns) {
			boolean b1 = MarketSegment.marketSegmentSubset(o.getMarketSegment(), c.getMarketSegment());
			boolean b2 = MarketSegment.marketSegmentSubset(c.getMarketSegment(), o.getMarketSegment());
			if (b1 || b2) {
				score += 1;
			} else if (this.overlap(o.getMarketSegment(), c.getMarketSegment())) {
				score += 0.5;
			}
		}
		return score;
	}
	
	protected boolean overlap(MarketSegment m1, MarketSegment m2) {
		Set<MarketSegment> subs2 = this.subsets.get(m2);
		for(MarketSegment sub : this.subsets.get(m1)) {
			if(subs2.contains(sub)) {
				return true;
			}
		}
		return false;
	}
	
//	private boolean isSubsetSuperset(Campaign c) {
//		for (Campaign d : this.getActiveCampaigns()) {
//			boolean b1 = MarketSegment.marketSegmentSubset(d.getMarketSegment(), c.getMarketSegment());
//			boolean b2 = MarketSegment.marketSegmentSubset(c.getMarketSegment(), d.getMarketSegment());
//			if (b1 || b2) {
//				return true;
//			}
//		}
//		return false;
//	}
	
	private boolean isDailyCampaign(Campaign c) {
		return c.getReach() == c.getBudget();
	}

	public static void main(String[] args) throws IOException, AdXException {
		// Here's an opportunity to test offline against some TA agents. Just run
		// this file in Eclipse to do so.
		// Feel free to change the type of agents.
		// Note: this runs offline, so:
		// a) It's much faster than the online test; don't worry if there's no delays.
		// b) You should still run the test script mentioned in the handout to make sure
		// your agent works online.
		if (args.length == 0) {
			Map<String, AgentLogic> test_agents = new ImmutableMap.Builder<String, AgentLogic>()
					.put("me", new MyNDaysNCampaignsAgent())
					.put("opponent_1", new Tier1NDaysNCampaignsAgent())
					.put("opponent_2", new Tier1NDaysNCampaignsAgent())
					.put("opponent_3", new Tier1NDaysNCampaignsAgent())
					.put("opponent_4", new Tier1NDaysNCampaignsAgent())
					.put("opponent_5", new Tier1NDaysNCampaignsAgent())
					.put("opponent_6", new Tier1NDaysNCampaignsAgent())
					.put("opponent_7", new Tier1NDaysNCampaignsAgent())
					.put("opponent_8", new Tier1NDaysNCampaignsAgent())
					.put("opponent_9", new Tier1NDaysNCampaignsAgent()).build();

			// Don't change this.
			OfflineGameServer.initParams(new String[] { "offline_config.ini", "CS1951K-FINAL" });
			
			AgentStartupUtil.testOffline(test_agents, new OfflineGameServer());
		} else {
			// Don't change this.
			AgentStartupUtil.startOnline(new MyNDaysNCampaignsAgent(), args, NAME);
		}
	}

}
