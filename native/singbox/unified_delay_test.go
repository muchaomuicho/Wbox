package urltest

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type delayedDialer struct {
	delay time.Duration
	calls atomic.Int32
}

func (d *delayedDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	d.calls.Add(1)
	select {
	case <-time.After(d.delay):
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	return (&net.Dialer{}).DialContext(ctx, network, destination.String())
}
func (d *delayedDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return (&net.ListenConfig{}).ListenPacket(ctx, "udp", "127.0.0.1:0")
}

func TestUnifiedDelayExcludesHandshake(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		time.Sleep(60 * time.Millisecond)
		w.WriteHeader(204)
	}))
	defer server.Close()
	dialer := &delayedDialer{delay: 300 * time.Millisecond}
	result, err := URLTest(context.Background(), server.URL, dialer)
	if err != nil || result < 45 || result >= 250 || requests.Load() != 2 || dialer.calls.Load() != 1 {
		t.Fatalf("delay=%d err=%v requests=%d dials=%d", result, err, requests.Load(), dialer.calls.Load())
	}
}

func TestUnifiedDelayNoReuseKeepsFirstMeasurement(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Connection", "close")
		time.Sleep(30 * time.Millisecond)
		w.WriteHeader(204)
	}))
	defer server.Close()
	result, err := URLTest(context.Background(), server.URL, &delayedDialer{})
	if err != nil || result < 20 || result >= 250 {
		t.Fatalf("delay=%d err=%v", result, err)
	}
}

func TestUnifiedDelayCancellationAndFailure(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if requests.Add(1) == 2 {
			cancel()
			<-r.Context().Done()
			return
		}
		w.WriteHeader(204)
	}))
	defer server.Close()
	if result, err := URLTest(ctx, server.URL, &delayedDialer{}); err == nil || result != 0 {
		t.Fatalf("cancellation: delay=%d err=%v", result, err)
	}
	cancelled, stop := context.WithCancel(context.Background())
	stop()
	if result, err := URLTest(cancelled, server.URL, &delayedDialer{}); err == nil || result != 0 {
		t.Fatalf("failure: delay=%d err=%v", result, err)
	}
}

func TestUnifiedDelayNeverReportsZeroOnSuccess(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer server.Close()
	if result, err := URLTest(context.Background(), server.URL, &delayedDialer{}); err != nil || result == 0 {
		t.Fatalf("delay=%d err=%v", result, err)
	}
}
