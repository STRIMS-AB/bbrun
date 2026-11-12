package org.bbrun.runtime;

import org.bbrun.BBRun;
import org.bbrun.config.BBRunConfig;
import org.bbrun.config.ConfigLoader;
import org.bbrun.interpreter.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the non-HTTP resource types: {@code file} (local file
 * storage), {@code sql} (in-memory mirror of a SQL database), {@code queue}
 * (in-memory FIFO) and {@code email} (outbox sink).
 */
public class ResourceTypeExtensionsTest {

    @TempDir
    Path tmpDir;

    private ExecutionResult run(String yaml, String script) {
        BBRunConfig config = ConfigLoader.parse(yaml, tmpDir);
        BBRun bbrun = BBRun.builder().withConfig(config).withEnvironment("local").build();
        return bbrun.execute(script);
    }

    // ---------------------------------------------------------------
    // file
    // ---------------------------------------------------------------

    @Test
    void filePutGetListDelete() {
        String yaml = """
                version: "1.0"
                environments:
                  local:
                    resources:
                      drafts:
                        type: file
                        dir: data/drafts
                """;
        String script = """
                drafts.put("notes/hello.txt", "hello world")
                let stored = drafts.get("notes/hello.txt")
                assert stored.exists == true
                assert stored.content == "hello world"
                let entries = drafts.list("notes")
                assert entries.length == 1
                assert entries[0].path == "notes/hello.txt"
                drafts.delete("notes/hello.txt")
                assert drafts.get("notes/hello.txt").exists == false
                """;
        ExecutionResult res = run(yaml, script);
        assertTrue(res.isSuccess(), "file lifecycle failed: " + res.errorMessage());
        assertTrue(Files.exists(tmpDir.resolve("data/drafts/notes/hello.txt").getParent())
                && !Files.exists(tmpDir.resolve("data/drafts/notes/hello.txt")));
    }

    @Test
    void filePutSerializesObjectContent() {
        String yaml = """
                version: "1.0"
                environments:
                  local:
                    resources:
                      drafts:
                        type: file
                        dir: files
                """;
        String script = """
                drafts.put("config/env.json", { "region": "eu", "count": 3 })
                let stored = drafts.get("config/env.json")
                assert stored.exists == true
                assert stored.content contains "eu"
                assert stored.content contains "3"
                """;
        ExecutionResult res = run(yaml, script);
        assertTrue(res.isSuccess(), "file object write failed: " + res.errorMessage());
    }

    @Test
    void fileRejectsPathsThatEscapeTheRoot() {
        String yaml = """
                version: "1.0"
                environments:
                  local:
                    resources:
                      drafts:
                        type: file
                        dir: files
                """;
        ExecutionResult res = run(yaml, "let got = drafts.get(\"../secret.txt\")");
        assertFalse(res.isSuccess());
        assertTrue(res.errorMessage().contains("escapes the configured dir"), res.errorMessage());
    }

    // ---------------------------------------------------------------
    // sql
    // ---------------------------------------------------------------

    @Test
    void sqlQueryIsSelectOrderedLimited() {
        String yaml = sqlSeedYaml();
        String script = """
                let active = customers.query("select * from customers where active = true")
                assert active.length == 2
                assert active[0].email == "a@b.com"
                let newest = customers.query("select * from customers order by id desc limit 1")
                assert newest.length == 1
                assert newest[0].id == 3
                """;
        ExecutionResult res = run(yaml, script);
        assertTrue(res.isSuccess(), "sql query failed: " + res.errorMessage());
    }

    @Test
    void sqlExecuteInsertsUpdatesDeletes() {
        String yaml = sqlSeedYaml();
        String script = """
                customers.execute("insert into customers (id, email, active) values (4, 'z@z.com', true)")
                let all = customers.query("select * from customers")
                assert all.length == 4
                customers.execute("update customers set active = false where email = 'a@b.com'")
                let updated = customers.query("select * from customers where active = false")
                assert updated.length == 2
                customers.execute("delete from customers where active = true")
                let left = customers.query("select * from customers")
                assert left.length == 2
                """;
        ExecutionResult res = run(yaml, script);
        assertTrue(res.isSuccess(), "sql execute failed: " + res.errorMessage());
    }

    @Test
    void sqlTableCanBeCreatedAndInsertedWithoutExplicitColumns() {
        String yaml = sqlSeedYaml();
        String script = """
                customers.execute("create table logs (id int primary key, message text)")
                customers.execute("insert into logs values (1, 'hello')")
                let rows = customers.query("select * from logs")
                assert rows.length == 1
                assert rows[0].message == "hello"
                """;
        ExecutionResult res = run(yaml, script);
        assertTrue(res.isSuccess(), "sql create/insert failed: " + res.errorMessage());
    }

    @Test
    void sqlQueryRequiresSelectAndExecuteRequiresMutation() {
        String yaml = sqlSeedYaml();
        ExecutionResult wrong = run(yaml, "let r = customers.query(\"update customers set active = false\")");
        assertFalse(wrong.isSuccess());
        assertTrue(wrong.errorMessage().contains("'query' expects a SELECT"), wrong.errorMessage());

        ExecutionResult mutation = run(yaml, "let r = customers.execute(\"select * from customers\")");
        assertFalse(mutation.isSuccess());
        assertTrue(mutation.errorMessage().contains("'execute' expects INSERT/UPDATE/DELETE/CREATE"), mutation.errorMessage());
    }

    @Test
    void sqlUnknownTableAndUnknownOperationFail() {
        String yaml = sqlSeedYaml();
        ExecutionResult table = run(yaml, "let r = customers.query(\"select * from nope\")");
        assertFalse(table.isSuccess());
        assertTrue(table.errorMessage().contains("unknown table"), table.errorMessage());

        ExecutionResult op = run(yaml, "let r = customers.send({})");
        assertFalse(op.isSuccess());
        assertTrue(op.errorMessage().contains("does not support operation 'send'"), op.errorMessage());
    }

    private String sqlSeedYaml() {
        return """
                version: "1.0"
                environments:
                  local:
                    resources:
                      customers:
                        type: sql
                        data:
                          customers:
                            - { id: 1, email: a@b.com, active: true }
                            - { id: 2, email: c@d.com, active: true }
                            - { id: 3, email: e@f.com, active: false }
                """;
    }

    // ---------------------------------------------------------------
    // queue
    // ---------------------------------------------------------------

    @Test
    void queueSendReceivePeekFifo() {
        String yaml = """
                version: "1.0"
                environments:
                  local:
                    resources:
                      jobs:
                        type: queue
                """;
        String script = """
                jobs.send({ kind: "invoice", amount: 1550 })
                jobs.send({ kind: "receipt", amount: 10 })
                assert jobs.receive().kind == "invoice"
                assert jobs.peek().kind == "receipt"
                assert jobs.receive().kind == "receipt"
                jobs.send({ kind: "done" })
                assert jobs.receive().kind == "done"
                """;
        // queue storage is shared by name across the JVM; drain first in case a
        // previous test left messages in this queue.
        run(yaml, "let surplus = jobs.receive()\nprint surplus");
        ExecutionResult res = run(yaml, script);
        assertTrue(res.isSuccess(), "queue fifo failed: " + res.errorMessage());
    }

    @Test
    void queueReceiveOnEmptyIsEmptyBody() {
        String yaml = """
                version: "1.0"
                environments:
                  local:
                    resources:
                      emptyq:
                        type: queue
                """;
        String script = """
                let nothing = emptyq.receive()
                assert nothing == null
                """;
        ExecutionResult res = run(yaml, script);
        assertTrue(res.isSuccess(), "empty queue receive failed: " + res.errorMessage());
    }

    // ---------------------------------------------------------------
    // email
    // ---------------------------------------------------------------

    @Test
    void emailSendAndOutbox() {
        String yaml = """
                version: "1.0"
                environments:
                  local:
                    resources:
                      mailer:
                        type: email
                        from: tests@example.com
                """;
        String script = """
                let accepted = mailer.send({ to: "ops@example.com", subject: "All good", body: "Ship it" })
                assert accepted.accepted == true
                mailer.send({ to: "dev@example.com", subject: "Review", body: "Please look" })
                let sent = mailer.outbox()
                assert sent.length == 2
                assert sent[0].subject == "All good"
                assert sent[0].from == "tests@example.com"
                assert sent[1].to contains "dev@example.com"
                """;
        ExecutionResult res = run(yaml, script);
        assertTrue(res.isSuccess(), "email send failed: " + res.errorMessage());
    }

    @Test
    void emailRequiresRecipientAndSubject() {
        String yaml = """
                version: "1.0"
                environments:
                  local:
                    resources:
                      mailer:
                        type: email
                """;
        ExecutionResult noSubject = run(yaml, "let ack = mailer.send({ to: \"x@y.z\" })");
        assertFalse(noSubject.isSuccess());
        assertTrue(noSubject.errorMessage().contains("'subject'"), noSubject.errorMessage());

        ExecutionResult noRecipient = run(yaml, "let ack = mailer.send({ subject: \"Hi\" })");
        assertFalse(noRecipient.isSuccess());
        assertTrue(noRecipient.errorMessage().contains("'to'"), noRecipient.errorMessage());
    }
}