from pathlib import Path
import tempfile
import unittest

from backend.server import ui_revision


class UiRevisionTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory()
        self.root=Path(self.directory.name)
        (self.root/'index.html').write_text('<link href="style.css"><script src="app.js"></script>')
        (self.root/'style.css').write_text('body{color:black}')
        (self.root/'app.js').write_text('console.log("synthetic")')

    def tearDown(self): self.directory.cleanup()

    def test_css_javascript_and_asset_changes_change_revision(self):
        before=ui_revision(self.root)
        for name,data in [('style.css',b'body{color:blue}'),('app.js',b'console.log("updated")'),('mark.png',b'synthetic-image')]:
            (self.root/name).write_bytes(data)
            after=ui_revision(self.root)
            self.assertNotEqual(before,after)
            self.assertRegex(after,r'^[0-9a-f]{64}$')
            before=after

    def test_private_and_nonserved_files_do_not_participate(self):
        before=ui_revision(self.root)
        (self.root/'.env').write_text('SYNTHETIC_PRIVATE_SENTINEL')
        (self.root/'credentials.json').write_text('SYNTHETIC_PRIVATE_SENTINEL')
        self.assertEqual(before,ui_revision(self.root))

    def test_revision_is_independent_of_checkout_location(self):
        with tempfile.TemporaryDirectory() as other:
            for source in self.root.iterdir():
                (Path(other)/source.name).write_bytes(source.read_bytes())
            self.assertEqual(ui_revision(self.root),ui_revision(Path(other)))

    def test_missing_entry_cannot_match_an_empty_project(self):
        (self.root/'index.html').unlink()
        with self.assertRaises(FileNotFoundError): ui_revision(self.root)


if __name__=='__main__': unittest.main()
