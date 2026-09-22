# What Play actually holds for each track: the release status and the fraction
# of users it is going out to.
#
# `google_play_track_version_codes` answers only which codes are on a track, and
# a staged rollout is entirely in the half it does not answer: a userFraction is
# honoured while a release is inProgress, and a release that reached `completed`
# has gone to everyone whatever fraction was asked for.
#
# Copy this into the app's own `fastlane/` directory and run it from the Android
# module:
#
#   bundle exec ruby fastlane/track_state.rb                    # the usual two
#   bundle exec ruby fastlane/track_state.rb production alpha   # or name them
#
# Reads the package from the Appfile so there is one place to change it, and the
# service account key from the repo root, where every Snapp app keeps it.
require "google/apis/androidpublisher_v3"
require "googleauth"

APPFILE = File.expand_path("Appfile", __dir__)
PACKAGE = ENV["PACKAGE_NAME"] ||
  File.read(APPFILE)[/package_name\s*\(?\s*["']([^"']+)["']/, 1] ||
  abort("No package: set PACKAGE_NAME or add package_name to #{APPFILE}")

KEY = ENV["SUPPLY_JSON_KEY"] ||
  File.expand_path("../../../play-service-account.json", __dir__)
abort("No service account key at #{KEY}") unless File.exist?(KEY)

TRACKS = ARGV.empty? ? %w[production internal] : ARGV

service = Google::Apis::AndroidpublisherV3::AndroidPublisherService.new
service.authorization = Google::Auth::ServiceAccountCredentials.make_creds(
  json_key_io: File.open(KEY),
  scope: "https://www.googleapis.com/auth/androidpublisher"
)

edit = service.insert_edit(PACKAGE)
begin
  TRACKS.each do |name|
    track = service.get_edit_track(PACKAGE, edit.id, name)
    releases = track.releases.to_a
    puts "#{name}: no releases" if releases.empty?
    releases.each do |r|
      puts "#{name}: codes=#{r.version_codes.inspect} status=#{r.status} " \
           "fraction=#{r.user_fraction.inspect} name=#{r.name.inspect}"
    end
  end
ensure
  # An edit left open blocks the next one, and this one changes nothing.
  service.delete_edit(PACKAGE, edit.id)
end
