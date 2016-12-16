package au.edu.wehi.idsv.debruijn.positional;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

import au.edu.wehi.idsv.AssemblyEvidenceSource;
import au.edu.wehi.idsv.BreakendDirection;
import au.edu.wehi.idsv.Defaults;
import au.edu.wehi.idsv.DirectedEvidence;
import au.edu.wehi.idsv.ProcessingContext;
import au.edu.wehi.idsv.configuration.AssemblyConfiguration;
import au.edu.wehi.idsv.configuration.VisualisationConfiguration;
import au.edu.wehi.idsv.visualisation.PositionalDeBruijnGraphTracker;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.util.Log;

/**
 * Assemblies non-reference breakend contigs
 * 
 * @author Daniel Cameron
 *
 */
public class PositionalAssembler implements Iterator<SAMRecord> {
	private static final Log log = Log.getInstance(PositionalAssembler.class);
	private final ProcessingContext context;
	private final AssemblyEvidenceSource source;
	private final PeekingIterator<DirectedEvidence> it;
	private final BreakendDirection direction;
	private NonReferenceContigAssembler currentAssembler = null;
	private String currentContig = "";
	public PositionalAssembler(ProcessingContext context, AssemblyEvidenceSource source, Iterator<DirectedEvidence> backingIterator, BreakendDirection direction) {
		this.context = context;
		this.source = source;
		this.direction = direction;
		if (direction != null) {
			backingIterator = Iterators.filter(backingIterator, x -> x.getBreakendSummary() != null && x.getBreakendSummary().direction == this.direction);
		}
		this.it = Iterators.peekingIterator(backingIterator);
	}
	public PositionalAssembler(ProcessingContext context, AssemblyEvidenceSource source, Iterator<DirectedEvidence> it) {
		this(context, source, it, null);
	}
	@Override
	public boolean hasNext() {
		ensureAssembler(Defaults.ATTEMPT_ASSEMBLY_RECOVERY);
		return currentAssembler != null && currentAssembler.hasNext();
	}
	@Override
	public SAMRecord next() {
		ensureAssembler(Defaults.ATTEMPT_ASSEMBLY_RECOVERY);
		return currentAssembler.next();
	}
	private void flushIfRequired() {
		if (currentAssembler != null && !currentAssembler.hasNext()) {
			if (currentAssembler.getExportTracker() != null) {
				try {
					currentAssembler.getExportTracker().close();
				} catch (IOException e) {
					log.debug(e);
				}
			}
			currentAssembler = null;
		}
	}
	private void ensureAssembler(boolean attemptRecovery) {
		try {
			ensureAssembler();
		} catch (AssertionError|Exception e) {
			String msg = "Fatal error assembling " + currentContig + ". This should not happen. Please raise an issue at https://github.com/PapenfussLab/gridss/issues";
			if (!attemptRecovery) {
				log.error(e, msg);
				throw e;
			} else {
				if (it.hasNext()) {
					msg = String.format("%s. Attempting recovery by resuming assembly at %s:%d",
							msg,
							context.getReference().getSequenceDictionary().getSequence(it.peek().getBreakendSummary().referenceIndex).getSequenceName(),
							it.peek().getBreakendSummary().start);
				}
				log.error(e, msg);
			}
		}
		ensureAssembler(); // don't attempt to recover again if our recovery attempt just failed
	}
	private void ensureAssembler() {
		flushIfRequired();
		while ((currentAssembler == null || !currentAssembler.hasNext()) && it.hasNext()) {
			// traverse contigs until we find one that has an assembly to call
			currentAssembler = createAssembler();
			flushIfRequired();
		}
	}
	private NonReferenceContigAssembler createAssembler() {
		AssemblyConfiguration ap = context.getAssemblyParameters();
		int maxKmerSupportIntervalWidth = source.getMaxConcordantFragmentSize() - source.getMinConcordantFragmentSize() + 1; 		
		int maxReadLength = source.getMaxReadLength();
		int k = ap.k;
		int maxEvidenceSupportIntervalWidth = maxKmerSupportIntervalWidth + maxReadLength - k + 2;
		int maxPathLength = ap.positional.maxPathLengthInBases(maxReadLength);
		int maxPathCollapseLength = ap.errorCorrection.maxPathCollapseLengthInBases(maxReadLength);
		int anchorAssemblyLength = ap.anchorLength;
		int referenceIndex = it.peek().getBreakendSummary().referenceIndex;
		currentContig = context.getDictionary().getSequence(referenceIndex).getSequenceName();
		ReferenceIndexIterator evidenceIt = new ReferenceIndexIterator(it, referenceIndex);
		EvidenceTracker evidenceTracker = new EvidenceTracker();
		SupportNodeIterator supportIt = new SupportNodeIterator(k, evidenceIt, source.getMaxConcordantFragmentSize(), evidenceTracker, ap.includePairAnchors, ap.pairAnchorMismatchIgnoreEndBases);
		AggregateNodeIterator agIt = new AggregateNodeIterator(supportIt);
		Iterator<KmerNode> knIt = agIt;
		if (Defaults.SANITY_CHECK_DE_BRUIJN) {
			knIt = evidenceTracker.new AggregateNodeAssertionInterceptor(knIt);
		}
		PathNodeIterator pathNodeIt = new PathNodeIterator(knIt, maxPathLength, k); 
		Iterator<KmerPathNode> pnIt = pathNodeIt;
		if (Defaults.SANITY_CHECK_DE_BRUIJN) {
			pnIt = evidenceTracker.new PathNodeAssertionInterceptor(pnIt, "PathNodeIterator");
		}
		CollapseIterator collapseIt = null;
		PathSimplificationIterator simplifyIt = null;
		if (ap.errorCorrection.maxBaseMismatchForCollapse > 0) {
			if (!ap.errorCorrection.collapseBubblesOnly) {
				log.warn("Collapsing all paths is an exponential time operation. Gridss is likely to hang if your genome contains repetative sequence");
				collapseIt = new PathCollapseIterator(pnIt, k, maxPathCollapseLength, ap.errorCorrection.maxBaseMismatchForCollapse, false, 0);
			} else {
				collapseIt = new LeafBubbleCollapseIterator(pnIt, k, maxPathCollapseLength, ap.errorCorrection.maxBaseMismatchForCollapse);
			}
			pnIt = collapseIt;
			if (Defaults.SANITY_CHECK_DE_BRUIJN) {
				pnIt = evidenceTracker.new PathNodeAssertionInterceptor(pnIt, "PathCollapseIterator");
			}
			simplifyIt = new PathSimplificationIterator(pnIt, maxPathLength, maxKmerSupportIntervalWidth);
			pnIt = simplifyIt;
			if (Defaults.SANITY_CHECK_DE_BRUIJN) {
				pnIt = evidenceTracker.new PathNodeAssertionInterceptor(pnIt, "PathSimplificationIterator");
			}
		}
		currentAssembler = new NonReferenceContigAssembler(pnIt, referenceIndex, maxEvidenceSupportIntervalWidth, anchorAssemblyLength, k, source, evidenceTracker, currentContig);
		VisualisationConfiguration vis = context.getConfig().getVisualisation();
		if (vis.assemblyProgress) {
			String filename = String.format("positional-%s-%s.csv", context.getDictionary().getSequence(referenceIndex).getSequenceName(), direction);
			File file = new File(vis.directory, filename);
			PositionalDeBruijnGraphTracker exportTracker;
			try {
				exportTracker = new PositionalDeBruijnGraphTracker(file, supportIt, agIt, pathNodeIt, collapseIt, simplifyIt, evidenceTracker, currentAssembler);
				exportTracker.writeHeader();
				currentAssembler.setExportTracker(exportTracker);
			} catch (IOException e) {
				log.debug(e);
			}
		}
		return currentAssembler;
	}
	private static class ReferenceIndexIterator implements PeekingIterator<DirectedEvidence> {
		private final PeekingIterator<DirectedEvidence> it;
		private final int referenceIndex;
		public ReferenceIndexIterator(PeekingIterator<DirectedEvidence> it, int referenceIndex) {
			this.it = it;
			this.referenceIndex = referenceIndex;
		}
		@Override
		public boolean hasNext() {
			return it.hasNext() && it.peek().getBreakendSummary().referenceIndex == referenceIndex;
		}

		@Override
		public DirectedEvidence next() {
			if (!hasNext()) throw new NoSuchElementException();
			return it.next();
		}

		@Override
		public DirectedEvidence peek() {
			if (hasNext()) return it.peek();
			throw new NoSuchElementException();
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
		
	}
}
