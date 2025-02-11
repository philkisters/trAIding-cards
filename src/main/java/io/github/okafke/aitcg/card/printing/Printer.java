package io.github.okafke.aitcg.card.printing;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

// TODO: solve this with a blocking thread that we notify, instead of polling?
@Getter
@ToString
@EqualsAndHashCode
@RequiredArgsConstructor(onConstructor_={@Autowired})
public class Printer {
    private final Deque<IppPrintJob> jobs = new LinkedList<>();
    private final URI printerIp;
    private long lastJob;

    public void update() {
        if (System.nanoTime() - lastJob < TimeUnit.SECONDS.toNanos(60)) {
            return;
        }

        IppPrintJob job = jobs.poll();
        if (job != null) {
            try {
                job.print();
            } finally {
                lastJob = System.nanoTime();
            }
        }
    }

}
