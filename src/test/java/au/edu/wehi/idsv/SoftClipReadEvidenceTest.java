package au.edu.wehi.idsv;

import static org.junit.Assert.*;

import org.junit.Test;

import htsjdk.samtools.SAMRecord;

public class SoftClipReadEvidenceTest extends TestHelper {
	static {
		//  1234567890
		//  ATGTGGC
		//  SSMMSSSH
		SAMRecord r = Read(2, 3, "2S2M3S5H");
		r.setReadBases(B("ATGTGGC"));
		r.setBaseQualities(B("1234567"));
		r.setMappingQuality(40);
		fExample = SoftClipEvidence.create(SES(), FWD, r);
		bExample = SoftClipEvidence.create(SES(), BWD, r);
	}
	private static final SoftClipEvidence fExample;
	private static final SoftClipEvidence bExample;
	@Test
	public void should_break_at_clip() {
		assertEquals(new BreakendSummary(2, FWD, 4), fExample.getBreakendSummary());
		assertEquals(new BreakendSummary(2, BWD, 3), bExample.getBreakendSummary());
	}
	@Test
	public void indels_should_have_unique_evidenceID() {
		assertNotEquals(fExample.getEvidenceID(), bExample.getEvidenceID());
	}
	@Test
	public void getBreakendSequence_should_return_sc() {
		assertEquals("GGC", S(fExample.getBreakendSequence()));
		assertEquals("AT", S(bExample.getBreakendSequence()));
	}
	
	@Test
	public void getBreakendQuality_should_return_sc_qual() {
		assertEquals("567", S(fExample.getBreakendQuality()));
		assertEquals("12", S(bExample.getBreakendQuality()));
	}
	
	@Test
	public void getAnchorSequence_should_exclude_soft_clipped_bases_on_other_side() {
		assertEquals("GT", S(fExample.getAnchorSequence()));
		assertEquals("GT", S(bExample.getAnchorSequence()));
	}
	
	@Test
	public void getAnchorQuality_should_return_local() {
		assertEquals("34", S(fExample.getAnchorQuality()));
		assertEquals("34", S(bExample.getAnchorQuality()));
	}
	
	@Test
	public void getMapq_should_match_read_mapq() {
		assertEquals(40, fExample.getLocalMapq());
		assertEquals(40, bExample.getLocalMapq());
	}
	
	@Test
	public void isBreakendExact() {
		assertTrue(fExample.isBreakendExact());
		assertTrue(bExample.isBreakendExact());
	}
	@Test
	public void getUntemplatedSequence_should_return_insert() {
		assertEquals(S(fExample.getBreakendSequence()), fExample.getUntemplatedSequence());
		assertEquals(S(bExample.getBreakendSequence()), bExample.getUntemplatedSequence());
	}
	@Test(expected=IllegalArgumentException.class)
	public void constructor_should_require_soft_clip_f() {
		SoftClipEvidence.create(SES(), FWD, Read(0, 10, "5S10M"));
	}
	@Test(expected=IllegalArgumentException.class)
	public void constructor_should_require_soft_clip_b() {
		SoftClipEvidence.create(SES(), BWD, Read(0, 10, "10M5S"));
	}
	@Test(expected=IllegalArgumentException.class)
	public void constructor_should_require_cigar() {
		SoftClipEvidence.create(SES(), BreakendDirection.Backward, new SAMRecord(getContext().getBasicSamHeader()));
	}
	@Test(expected=IllegalArgumentException.class)
	public void constructor_should_require_read_bases() {
		SoftClipEvidence.create(SES(), BreakendDirection.Backward, withSequence((byte[])null, Read(0, 1, "1S1M"))[0]);
	}
	@Test
	public void getEvidenceID_paired_should_encode_pair_info() {
		SAMRecord r = RP(1, 1, 100)[0];
		r.setReadName("ReadName");
		r.setCigarString("10M10S");
		r.setReadPairedFlag(true);
		r.setFirstOfPairFlag(true);
		String r1 = SoftClipEvidence.create(SES(), BreakendDirection.Forward, r).getEvidenceID();
		r.setFirstOfPairFlag(false);
		r.setSecondOfPairFlag(true);
		String r2 = SoftClipEvidence.create(SES(), BreakendDirection.Forward, r).getEvidenceID();
		assertNotEquals(r1, r2);
	}
	@Test
	public void getEvidenceID_unpaired_should_encode_read_direction() {
		SAMRecord r = Read(1, 1, 100);
		r.setReadName("ReadName");
		r.setCigarString("5S5M5S");
		r.setReadName("ReadName");
		assertNotEquals(SoftClipEvidence.create(SES(), BreakendDirection.Forward, r).getEvidenceID(),
				SoftClipEvidence.create(SES(), BreakendDirection.Backward, r).getEvidenceID());
	}
	@Test
	public void should_recognise_1XS_as_unanchored_sequence() {
		SoftClipEvidence e = SoftClipEvidence.create(SES(), FWD, withSequence("NCGT", Read(0, 100, "1X3S"))[0]);
		assertFalse(e.isBreakendExact());
		assertEquals("CGT", S(e.getBreakendSequence()));
		assertEquals("", S(e.getAnchorSequence()));
		assertEquals(new BreakendSummary(0, FWD, 100), e.getBreakendSummary());
	}
	@Test
	public void should_recognise_2XS_as_unanchored_sequence() {
		SoftClipEvidence e = SoftClipEvidence.create(SES(), FWD, withSequence("NNCGT", Read(0, 100, "2X3S"))[0]);
		assertFalse(e.isBreakendExact());
		assertEquals("CGT", S(e.getBreakendSequence()));
		assertEquals("", S(e.getAnchorSequence()));
		assertEquals(new BreakendSummary(0, FWD, 101, 100, 101), e.getBreakendSummary());
	}
	@Test
	public void should_recognise_XNX_as_unanchored_breakend_interval() {
		SoftClipEvidence e = SoftClipEvidence.create(SES(), FWD, withSequence("NNCGT", Read(0, 100, "1X1N1X3S"))[0]);
		assertFalse(e.isBreakendExact());
		assertEquals("CGT", S(e.getBreakendSequence()));
		assertEquals("", S(e.getAnchorSequence()));
		assertEquals(new BreakendSummary(0, FWD, 101, 100, 102), e.getBreakendSummary());
	}
	@Test
	public void should_recognise_SXNX_as_unanchored_sequence() {
		SoftClipEvidence e = SoftClipEvidence.create(SES(), BWD, withSequence("CGTNN", Read(0, 100, "3S1X2N1X"))[0]);
		assertFalse(e.isBreakendExact());
		assertEquals("CGT", S(e.getBreakendSequence()));
		assertEquals("", S(e.getAnchorSequence()));
		assertEquals(new BreakendSummary(0, BWD, 101, 100, 103), e.getBreakendSummary());
	}
}