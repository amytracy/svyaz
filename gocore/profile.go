package gocore

import (
	"archive/zip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/libp2p/go-libp2p/core/crypto"
)

// A profile archive holds the same files as a desktop --data directory, so it
// can be unpacked there directly. identity.key must be written last on import.
var profileFiles = []string{"state.json", "sequence.json", "identity.key"}

const maxProfileFile = 512 << 20

// ExportProfile writes an unencrypted zip of the profile to path.
// The node must be stopped so that the files are consistent.
func ExportProfile(dataDir, path string) (err error) {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	if active() != nil {
		return errors.New("stop the node before exporting")
	}
	if _, err = os.Stat(filepath.Join(dataDir, "identity.key")); err != nil {
		return err
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer func() {
		if cerr := f.Close(); err == nil {
			err = cerr
		}
		if err != nil {
			os.Remove(path)
		}
	}()
	z := zip.NewWriter(f)
	for _, name := range profileFiles {
		raw, rerr := os.ReadFile(filepath.Join(dataDir, name))
		if os.IsNotExist(rerr) {
			continue
		}
		if rerr != nil {
			return rerr
		}
		w, werr := z.Create(name)
		if werr != nil {
			return werr
		}
		if _, err = w.Write(raw); err != nil {
			return err
		}
	}
	return z.Close()
}

func readProfile(path string) (map[string][]byte, error) {
	z, err := zip.OpenReader(path)
	if err != nil {
		return nil, errors.New("файл не является архивом профиля")
	}
	defer z.Close()
	known := map[string]bool{}
	for _, name := range profileFiles {
		known[name] = true
	}
	out := map[string][]byte{}
	for _, f := range z.File {
		if !known[f.Name] {
			continue
		}
		if f.UncompressedSize64 > maxProfileFile {
			return nil, fmt.Errorf("%s: файл слишком большой", f.Name)
		}
		r, err := f.Open()
		if err != nil {
			return nil, err
		}
		raw, err := io.ReadAll(io.LimitReader(r, maxProfileFile+1))
		r.Close()
		if err != nil {
			return nil, err
		}
		if len(raw) > maxProfileFile {
			return nil, fmt.Errorf("%s: файл слишком большой", f.Name)
		}
		out[f.Name] = raw
	}
	return out, nil
}

func validateProfile(files map[string][]byte) error {
	key, ok := files["identity.key"]
	if !ok {
		return errors.New("в архиве нет identity.key")
	}
	if _, err := crypto.UnmarshalPrivateKey(key); err != nil {
		return errors.New("identity.key повреждён")
	}
	if raw, ok := files["state.json"]; ok {
		var s DiskState
		if err := json.Unmarshal(raw, &s); err != nil {
			return errors.New("state.json повреждён")
		}
		if len(s.Messages) > maxHistory {
			return errors.New("история в архиве превышает лимит")
		}
	}
	if raw, ok := files["sequence.json"]; ok {
		var seq uint64
		if err := json.Unmarshal(raw, &seq); err != nil {
			return errors.New("sequence.json повреждён")
		}
	}
	return nil
}

// ImportProfile validates the archive at path and replaces the profile in
// dataDir. The node must be stopped. Nothing is changed if validation fails.
func ImportProfile(dataDir, path string) error {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	if active() != nil {
		return errors.New("stop the node before importing")
	}
	files, err := readProfile(path)
	if err != nil {
		return err
	}
	if err = validateProfile(files); err != nil {
		return err
	}
	if err = prepareDir(dataDir); err != nil {
		return err
	}
	for _, name := range profileFiles {
		target := filepath.Join(dataDir, name)
		raw, ok := files[name]
		if !ok {
			if err = os.Remove(target); err != nil && !os.IsNotExist(err) {
				return err
			}
			continue
		}
		if err = atomicWrite(target, raw); err != nil {
			return err
		}
	}
	return nil
}
