// Command relaysmoke is the Go side of heyarr-kmp's desktop-enrolment relay smoke
// test (RelaySmokeTest). It stands up a REAL void-which-binds-go relay and drives a
// REAL genesis initiator through the pairflow against it — the counterpart the Kotlin
// desktop responder (PairingCoordinator + PatientRelayTransport + DevicePairing)
// pairs with. Nothing here is a fake: the relay is void-which-binds-go/relay, the
// handshake + seal is void-which-binds-go/pairflow, and the invite is the same gen2
// void-which-binds:pair?v=4 URI void-which-binds-kmp's Invite.decode parses
// (void-which-binds-go v0.19.0+, ADR-0022: gen2 only).
//
// It is NOT built into the void-which-binds-go tree. The Kotlin test copies this file
// into a throwaway package directory *inside the void-which-binds-go module* at run
// time and `go run`s it, so its imports resolve against that module's own go.mod /
// module cache with no network. See RelaySmokeTest for the gating.
//
// Protocol with the Kotlin side (line-oriented over stdout; stderr is diagnostics):
//
//	INVITE <void-which-binds:pair?…>  emitted once the relay is up and the session is open;
//	                           the responder joins on this and nothing else
//	USER <ed25519:hex>         the genesis identity the new device is enrolled into
//	SAS <7 digits>             the initiator's derived SAS, after the handshake
//	DONE                       the initiator signed + sealed the add op (Authorise)
//
// After DONE the relay stays up (so the responder can still GET the sealed cert)
// until this process's stdin reaches EOF — the Kotlin test closes it once it has
// asserted ENROLLED, and this process then exits 0. Any failure prints
// "ERROR <msg>" to stderr and exits non-zero, which fails the Kotlin test.
package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"time"

	"github.com/rarebit-one/void-which-binds-go/identity"
	"github.com/rarebit-one/void-which-binds-go/pairflow"
	"github.com/rarebit-one/void-which-binds-go/relay"
)

// overallTimeout bounds the whole exchange so a responder that never joins (a
// broken Kotlin side) fails the run instead of hanging CI forever.
const overallTimeout = 120 * time.Second

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "ERROR %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, cancel := context.WithTimeout(context.Background(), overallTimeout)
	defer cancel()

	// A real relay on a loopback ephemeral port. SessionTTL generous so a slow
	// responder (the coordinator polls up to its TTL) is never evicted mid-pair.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("listening: %w", err)
	}
	srv := &http.Server{Handler: relay.NewServer(relay.Options{SessionTTL: 15 * time.Minute}).Routes()}
	go func() { _ = srv.Serve(ln) }()
	defer func() { _ = srv.Close() }()
	base := "http://" + ln.Addr().String()

	// A genesis initiator: the first, self-admitting member — the recovery key.
	// It admits the desktop responder as the identity's first device.
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return fmt.Errorf("genesis key: %w", err)
	}
	userID := identity.FormatPublicKey(pub)

	salt := make([]byte, 32)
	if _, err := rand.Read(salt); err != nil {
		return fmt.Errorf("salt: %w", err)
	}

	session, err := relay.CreateSession(ctx, nil, base)
	if err != nil {
		return fmt.Errorf("create session: %w", err)
	}

	invite, err := pairflow.EncodeInvite(base, session, salt, userID)
	if err != nil {
		return fmt.Errorf("encode invite: %w", err)
	}

	in, err := pairflow.NewInitiator(priv, salt, time.Now(), 0)
	if err != nil {
		return fmt.Errorf("initiator: %w", err)
	}

	// Announce the invite BEFORE blocking on the handshake — the responder needs
	// it to join, and Handshake below blocks fetching the responder's commitment.
	emit("INVITE " + invite)
	emit("USER " + userID)

	t := &relay.Client{Base: base, Session: session, Role: "initiator", PollInterval: 50 * time.Millisecond}

	sas, err := in.Handshake(ctx, t)
	if err != nil {
		return fmt.Errorf("handshake: %w", err)
	}
	emit("SAS " + string(sas))

	// No human gate in the smoke test: both sides derive the same SAS over the
	// relay, so we proceed straight to signing + sealing the add op. (The human
	// comparison is exercised by PairingCoordinatorTest's fakes; here we prove the
	// live wire.)
	if err := in.Authorise(ctx, t); err != nil {
		return fmt.Errorf("authorise: %w", err)
	}
	emit("DONE")

	// Hold the relay open until the responder has fetched the sealed cert and the
	// Kotlin test closes our stdin. A context timeout still bounds the wait.
	waited := make(chan struct{})
	go func() { _, _ = io.Copy(io.Discard, os.Stdin); close(waited) }()
	select {
	case <-waited:
	case <-ctx.Done():
	}
	return nil
}

func emit(line string) {
	fmt.Fprintln(os.Stdout, line)
	_ = os.Stdout.Sync()
}
