package ba.telco.ocs.cdr.pipeline;

import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.cdr.entity.CdrBatch;

record CdrJob(Cdr cdr, CdrBatch batch, long enqueuedAtNanos) {
}
