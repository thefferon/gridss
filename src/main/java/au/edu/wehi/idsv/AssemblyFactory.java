package au.edu.wehi.idsv;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import au.edu.wehi.idsv.util.CollectionUtil;
import au.edu.wehi.idsv.vcf.VcfAttributes;
import au.edu.wehi.idsv.vcf.VcfFilter;
import au.edu.wehi.idsv.vcf.VcfSvConstants;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class AssemblyFactory {
	/**
	 * Creates an assembly 
	 * @param processContext context
	 * @param source assembly source
	 * @param direction direction of breakend
	 * @param evidence evidence supporting the assembly breakend
	 * @param anchorReferenceIndex contig of anchored bases 
	 * @param anchorBreakendPosition genomic position of anchored base closest breakend
	 * @param anchoredBases number of anchored bases in assembly
	 * @param baseCalls assembly base sequence as per a positive strand read over the anchor
	 * @param baseQuals assembly base qualities
	 * @param normalBaseCount number of assembly bases contributed by normal evidence sources
	 * @param tumourBaseCount number of assembly bases contributed by tumour evidence sources
	 * @return assembly evidence for the given assembly
	 */
	public static AssemblyEvidence createAnchored(ProcessingContext processContext,
			AssemblyEvidenceSource source, BreakendDirection direction,
			Set<DirectedEvidence> evidence,
			int anchorReferenceIndex, int anchorBreakendPosition, int anchoredBases,
			byte[] baseCalls, byte[] baseQuals,
			int normalBaseCount, int tumourBaseCount) {
		BreakendSummary breakend = new BreakendSummary(anchorReferenceIndex, direction, anchorBreakendPosition, anchorBreakendPosition);
		return new SAMRecordAssemblyEvidence(breakend, source, direction, evidence, anchoredBases, baseCalls, baseQuals, getAttributes(evidence, normalBaseCount, tumourBaseCount));
	}
	/**
	 * Creates an assembly whose breakpoint cannot be exactly anchored to the reference  
	 * @param processContext context
	 * @param source assembly source
	 * @param direction direction of breakend
	 * @param evidence evidence supporting the assembly breakend
	 * @param baseCalls assembly base sequence as per a positive strand read into a putative anchor
	 * @param baseQuals assembly base qualities
	 * @param normalBaseCount number of assembly bases contributed by normal evidence sources
	 * @param tumourBaseCount number of assembly bases contributed by tumour evidence sources
	 * @return assembly evidence for the given assembly
	 */
	public static AssemblyEvidence createUnanchored(ProcessingContext processContext,
			AssemblyEvidenceSource source, BreakendDirection direction,
			Set<DirectedEvidence> evidence,
			byte[] baseCalls, byte[] baseQuals,
			int normalBaseCount, int tumourBaseCount) {
		return null;
	}
	private static Map<VcfAttributes, int[]> getAttributes(Set<DirectedEvidence> evidence, int normalBaseCount, int tumourBaseCount) {
		List<NonReferenceReadPair> rp = Lists.newArrayList(Iterables.filter(evidence, NonReferenceReadPair.class));
		List<SoftClipEvidence> sc = Lists.newArrayList(Iterables.filter(evidence, SoftClipEvidence.class));
		List<NonReferenceReadPair> rpNormal = Lists.newArrayList(Iterables.filter(rp, new Predicate<NonReferenceReadPair>() { public boolean apply(NonReferenceReadPair e) { return !((SAMEvidenceSource)e.getEvidenceSource()).isTumour(); } }) );
		List<NonReferenceReadPair> rpTumour = Lists.newArrayList(Iterables.filter(rp, new Predicate<NonReferenceReadPair>() { public boolean apply(NonReferenceReadPair e) { return ((SAMEvidenceSource)e.getEvidenceSource()).isTumour(); } }) );
		List<SoftClipEvidence> scNormal = Lists.newArrayList(Iterables.filter(sc, new Predicate<SoftClipEvidence>() { public boolean apply(SoftClipEvidence e) { return !((SAMEvidenceSource)e.getEvidenceSource()).isTumour(); } }) );
		List<SoftClipEvidence> scTumour = Lists.newArrayList(Iterables.filter(sc, new Predicate<SoftClipEvidence>() { public boolean apply(SoftClipEvidence e) { return ((SAMEvidenceSource)e.getEvidenceSource()).isTumour(); } }) );
		
		HashMap<VcfAttributes, int[]> attributes = new HashMap<>();
		attributes.put(VcfAttributes.ASSEMBLY_BASE_COUNT, new int[] { normalBaseCount, tumourBaseCount });		
		attributes.put(VcfAttributes.ASSEMBLY_READPAIR_COUNT, new int[] { rpNormal.size(), rpTumour.size() } );
		attributes.put(VcfAttributes.ASSEMBLY_SOFTCLIP_COUNT, new int[] { scNormal.size(), scTumour.size()} );
		Function<SoftClipEvidence, Integer> fscLen = new Function<SoftClipEvidence, Integer>() { public Integer apply(SoftClipEvidence e) { return e.getSoftClipLength(); } };
		Function<NonReferenceReadPair, Integer> frpReadLength = new Function<NonReferenceReadPair, Integer>() { public Integer apply(NonReferenceReadPair e) { return e.getNonReferenceRead().getReadLength(); } };
		int scLenN = CollectionUtil.maxInt(scNormal, fscLen, 0);
		int scLenT = CollectionUtil.maxInt(scTumour, fscLen, 0);
		//int scLen = Math.max(scLenN, scLenT);
		attributes.put(VcfAttributes.ASSEMBLY_SOFTCLIP_CLIPLENGTH_TOTAL, new int[] { CollectionUtil.sumInt(scNormal, fscLen), CollectionUtil.sumInt(scTumour, fscLen) } );
		attributes.put(VcfAttributes.ASSEMBLY_SOFTCLIP_CLIPLENGTH_MAX, new int[] { scLenN, scLenT } );
		int rpReadLenN = CollectionUtil.maxInt(rpNormal, frpReadLength, 0);
		int rpReadLenT = CollectionUtil.maxInt(rpTumour, frpReadLength, 0);
		//int rpReadLen = Math.max(rpReadLenN, rpReadLenT);
		attributes.put(VcfAttributes.ASSEMBLY_READPAIR_LENGTH_MAX, new int[] { rpReadLenN, rpReadLenT} );
		return attributes;
	}
}
